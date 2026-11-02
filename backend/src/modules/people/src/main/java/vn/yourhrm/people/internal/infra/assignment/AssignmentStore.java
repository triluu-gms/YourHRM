package vn.yourhrm.people.internal.infra.assignment;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.AssignmentEvents.CostShare;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrJson;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Bi-temporal writes and reads of {@code hr.assignment} (DESIGN §5.2, ADR-0024). Rows are never updated in place: a
 * version is superseded ({@code superseded_at}/{@code superseded_by}/{@code correction_reason}, the only columns
 * {@code sys.tg_bitemporal_guard} lets change) and a new version is inserted. Superseding and inserting the replacement
 * run as ONE statement, so that the exclusion constraint on current knowledge never sees both versions and the
 * {@code superseded_by} foreign key is checked after the replacement exists.
 *
 * <p>Pending requests (a job change waiting for approval) are kept as <em>proposal</em> rows: inserted already
 * superseded ({@code superseded_at = recorded_at}) with {@code correction_reason = 'PROPOSAL {json}'}. They are never
 * current knowledge and invisible to every as-of / as-known query (workaround: the DDL has no change-request table).
 *
 * <p>Must run inside a People transaction (the caller's {@code @HrTransactional}).
 */
@Component
public class AssignmentStore {

    /** SQL predicate (alias {@code a}) excluding proposal rows. */
    public static final String NOT_PROPOSAL =
            "not (a.superseded_at = a.recorded_at and coalesce(a.correction_reason, '') like 'PROPOSAL {%')";

    public static final String PROPOSAL_PREFIX = "PROPOSAL ";

    private static final String COLUMNS = """
            tenant_id, id, assignment_key, employment_id, assignment_type, legal_entity_id, paying_legal_entity_id,
            position_id, org_unit_id, job_title_id, grade_id, manager_employment_id, location_id, cost_centre_id,
            cost_split, fte, action_code, reason_code, decision_id, valid_from, valid_to, source, correction_reason,
            created_by, updated_by""";

    private static final String VALUES = """
            :t, :id, :key, :emp, :type, :le, :ple, :pos, :ou, :job, :grade, :mgr, :loc, :cc, cast(:split as jsonb),
            :fte, :action, :reason, :decision, :vf, :vt, :source, :corr, :actor, :actor""";

    private final HrDb db;
    private final HrJson json;

    public AssignmentStore(HrDb db, HrJson json) {
        this.db = db;
        this.json = json;
    }

    /** Inserts a current-knowledge version. */
    public void insert(AssignmentRow r) {
        bind(db.jdbc().sql("insert into hr.assignment (" + COLUMNS + ") values (" + VALUES + ")"), r).update();
    }

    /** Inserts a proposal (never current knowledge); {@code payload} is kept as JSON in {@code correction_reason}. */
    public void insertProposal(AssignmentRow r, Map<String, Object> payload) {
        AssignmentRow p = r.withCorrectionReason(PROPOSAL_PREFIX + json.write(payload));
        bind(db.jdbc().sql("insert into hr.assignment (" + COLUMNS + ", superseded_at) values (" + VALUES
                + ", now())"), p).update();
    }

    /**
     * Supersedes the current version {@code oldId} (with {@code oldReason} recorded on it, may be null) and inserts
     * {@code replacement} in the same statement; 409 when {@code oldId} is no longer current.
     */
    public void replace(UUID oldId, String oldReason, AssignmentRow replacement) {
        String sql = "with s as (update hr.assignment set superseded_at = now(), superseded_by = :id, "
                + "correction_reason = coalesce(:oldReason, correction_reason), updated_by = :actor "
                + "where tenant_id = :t and id = :oldId and superseded_at is null returning id) "
                + "insert into hr.assignment (" + COLUMNS + ") select " + VALUES + " from s";
        int n = bind(db.jdbc().sql(sql), replacement).param("oldId", oldId).param("oldReason", oldReason).update();
        if (n == 0) {
            throw ApiException.conflict("stale-assignment", "Assignment version " + oldId
                    + " is no longer the current one; reload and retry");
        }
    }

    /** Supersedes a current version without a replacement (e.g. a cancelled future change). */
    public void supersede(UUID id, String reason) {
        int n = db.jdbc().sql("""
                        update hr.assignment set superseded_at = now(), correction_reason = :reason, updated_by = :actor
                         where tenant_id = :t and id = :id and superseded_at is null""")
                .param("reason", reason).param("actor", TenantContext.current().userAccountId())
                .param("t", TenantContext.current().tenantId()).param("id", id).update();
        if (n == 0) {
            throw ApiException.conflict("stale-assignment", "Assignment version " + id
                    + " is no longer the current one; reload and retry");
        }
    }

    public Optional<AssignmentRow> find(UUID id) {
        return db.jdbc().sql("select a.* from hr.assignment a where a.tenant_id = :t and a.id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", id).query(this::map).optional();
    }

    /** The JSON payload of a proposal row; empty when {@code id} is not a proposal. */
    public Optional<Map<String, Object>> proposalPayload(AssignmentRow row) {
        String reason = row.correctionReason();
        if (reason == null || !reason.startsWith(PROPOSAL_PREFIX) || row.supersededAt() == null
                || !row.supersededAt().equals(row.recordedAt())) {
            return Optional.empty();
        }
        return Optional.of(json.map(reason.substring(PROPOSAL_PREFIX.length())));
    }

    /** Current-knowledge versions of an employment (all lines), ordered by line and valid_from. */
    public List<AssignmentRow> current(UUID employmentId) {
        return db.jdbc().sql("""
                        select a.* from hr.assignment a
                         where a.tenant_id = :t and a.employment_id = :e and a.superseded_at is null
                         order by a.assignment_type, a.assignment_key, a.valid_from""")
                .param("t", TenantContext.current().tenantId()).param("e", employmentId).query(this::map).list();
    }

    /** Current-knowledge versions of one assignment line, ordered by valid_from. */
    public List<AssignmentRow> line(UUID assignmentKey) {
        return db.jdbc().sql("""
                        select a.* from hr.assignment a
                         where a.tenant_id = :t and a.assignment_key = :k and a.superseded_at is null
                         order by a.valid_from""")
                .param("t", TenantContext.current().tenantId()).param("k", assignmentKey).query(this::map).list();
    }

    /** The PRIMARY version valid on {@code date} (current knowledge). */
    public Optional<AssignmentRow> primaryOn(UUID employmentId, LocalDate date) {
        return db.jdbc().sql("""
                        select a.* from hr.assignment a
                         where a.tenant_id = :t and a.employment_id = :e and a.assignment_type = 'PRIMARY'
                           and a.superseded_at is null and a.valid_during @> cast(:d as date)""")
                .param("t", TenantContext.current().tenantId()).param("e", employmentId).param("d", date)
                .query(this::map).optional();
    }

    /**
     * Versions valid on {@code asOf}: current knowledge when {@code knownBefore} is null, otherwise what was known
     * just before the instant {@code knownBefore} (exclusive bound, NFR-PPL-001).
     */
    public List<AssignmentRow> asOf(UUID employmentId, LocalDate asOf, Instant knownBefore) {
        String known = knownBefore == null ? "a.superseded_at is null"
                : "a.recorded_at < :k and (a.superseded_at is null or a.superseded_at >= :k) and " + NOT_PROPOSAL;
        var spec = db.jdbc().sql("select a.* from hr.assignment a where a.tenant_id = :t and a.employment_id = :e and "
                        + known + " and a.valid_during @> cast(:d as date) order by a.assignment_type, a.valid_from")
                .param("t", TenantContext.current().tenantId()).param("e", employmentId).param("d", asOf);
        if (knownBefore != null) {
            spec = spec.param("k", OffsetDateTime.ofInstant(knownBefore, ZoneOffset.UTC));
        }
        return spec.query(this::map).list();
    }

    /** Every version ever recorded (corrections and superseded versions included, proposals excluded). */
    public List<AssignmentRow> history(UUID employmentId) {
        return db.jdbc().sql("select a.* from hr.assignment a where a.tenant_id = :t and a.employment_id = :e and "
                        + NOT_PROPOSAL + " order by a.assignment_type, a.assignment_key, a.valid_from, a.recorded_at")
                .param("t", TenantContext.current().tenantId()).param("e", employmentId).query(this::map).list();
    }

    /** Current PRIMARY incumbent of a position on {@code date} (US-PPL-034). */
    public Optional<UUID> incumbent(UUID positionId, LocalDate date) {
        return db.jdbc().sql("""
                        select a.employment_id from hr.assignment a
                          join hr.employment e on e.tenant_id = a.tenant_id and e.id = a.employment_id
                         where a.tenant_id = :t and a.position_id = :p and a.assignment_type = 'PRIMARY'
                           and a.superseded_at is null and a.valid_during @> cast(:d as date)
                           and e.status not in ('CANCELLED', 'TERMINATED')
                         order by a.valid_from, a.employment_id limit 1""")
                .param("t", TenantContext.current().tenantId()).param("p", positionId).param("d", date)
                .query(UUID.class).optional();
    }

    public AssignmentRow map(ResultSet rs, int n) throws SQLException {
        return new AssignmentRow(rs.getObject("id", UUID.class), rs.getObject("assignment_key", UUID.class),
                rs.getObject("employment_id", UUID.class), rs.getString("assignment_type"),
                rs.getObject("legal_entity_id", UUID.class), rs.getObject("paying_legal_entity_id", UUID.class),
                rs.getObject("position_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                rs.getObject("job_title_id", UUID.class), rs.getObject("grade_id", UUID.class),
                rs.getObject("manager_employment_id", UUID.class), rs.getObject("location_id", UUID.class),
                rs.getObject("cost_centre_id", UUID.class), readSplit(rs.getString("cost_split")),
                rs.getBigDecimal("fte"), rs.getString("action_code"), rs.getString("reason_code"),
                rs.getObject("decision_id", UUID.class), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class), rs.getString("source"),
                instant(rs, "recorded_at"), instant(rs, "superseded_at"), rs.getObject("superseded_by", UUID.class),
                rs.getString("correction_reason"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private List<CostShare> readSplit(String value) {
        if (value == null) {
            return List.of();
        }
        List<CostShare> out = new ArrayList<>();
        for (Map<String, Object> m : json.listOfMaps(value)) {
            out.add(new CostShare(UUID.fromString(m.get("cost_centre_id").toString()),
                    new BigDecimal(m.get("pct").toString())));
        }
        return out;
    }

    private String writeSplit(List<CostShare> split) {
        if (split == null || split.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CostShare s : split) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cost_centre_id", s.costCentreId().toString());
            m.put("pct", s.pct());
            out.add(m);
        }
        return json.write(out);
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, AssignmentRow r) {
        TenantContext ctx = TenantContext.current();
        return spec.param("t", ctx.tenantId()).param("id", r.id()).param("key", r.assignmentKey())
                .param("emp", r.employmentId()).param("type", r.assignmentType()).param("le", r.legalEntityId())
                .param("ple", r.payingLegalEntityId()).param("pos", r.positionId()).param("ou", r.orgUnitId())
                .param("job", r.jobTitleId()).param("grade", r.gradeId()).param("mgr", r.managerEmploymentId())
                .param("loc", r.locationId()).param("cc", r.costCentreId()).param("split", writeSplit(r.costSplit()))
                .param("fte", r.fte()).param("action", r.actionCode()).param("reason", r.reasonCode())
                .param("decision", r.decisionId()).param("vf", r.validFrom()).param("vt", r.validTo())
                .param("source", r.source()).param("corr", r.correctionReason()).param("actor", ctx.userAccountId());
    }
}
