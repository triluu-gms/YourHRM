package vn.yourhrm.people.internal.infra.salary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrJson;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Bi-temporal writes of {@code hr.salary_record} with its append-only allowances (DESIGN §5.2), same technique as the
 * assignment store: supersede and insert the replacement in one statement; proposals (salary changes waiting for
 * approval) are rows inserted already superseded with {@code correction_reason = 'PROPOSAL {json}'}.
 *
 * <p>The reads here return amounts in clear and are for the change flows and Payroll's event only; user-facing reads
 * go through {@code hr.v_salary_record_masked} (SalaryService). Must run inside a People transaction.
 */
@Component
public class SalaryStore {

    public static final String NOT_PROPOSAL =
            "not (s.superseded_at = s.recorded_at and coalesce(s.correction_reason, '') like 'PROPOSAL {%')";
    public static final String PROPOSAL_PREFIX = "PROPOSAL ";

    private static final String COLUMNS = """
            tenant_id, id, employment_id, pay_basis, salary_kind, base_salary_amount, agreed_net_amount,
            net_guaranteed_scope, si_salary_amount, currency_code, pay_scale_code, pay_scale_step_code, probation_pct,
            action_code, reason_code, decision_id, contract_appendix_id, valid_from, valid_to, source,
            correction_reason, created_by, updated_by""";

    private static final String VALUES = """
            :t, :id, :emp, :basis, :kind, :base, :net, cast(:scope as jsonb), :si, :cur, :scale, :step, :prob,
            :action, :reason, :decision, :appendix, :vf, :vt, :source, :corr, :actor, :actor""";

    private final HrDb db;
    private final HrJson json;

    public SalaryStore(HrDb db, HrJson json) {
        this.db = db;
        this.json = json;
    }

    public void insert(SalaryRow r) {
        bind(db.jdbc().sql("insert into hr.salary_record (" + COLUMNS + ") values (" + VALUES + ")"), r).update();
        insertAllowances(r);
    }

    public void insertProposal(SalaryRow r, Map<String, Object> payload) {
        SalaryRow p = r.withCorrectionReason(PROPOSAL_PREFIX + json.write(payload));
        bind(db.jdbc().sql("insert into hr.salary_record (" + COLUMNS + ", superseded_at) values (" + VALUES
                + ", now())"), p).update();
        insertAllowances(p);
    }

    /** Supersedes current version {@code oldId} and inserts {@code replacement} in one statement; 409 when stale. */
    public void replace(UUID oldId, String oldReason, SalaryRow replacement) {
        String sql = "with s as (update hr.salary_record set superseded_at = now(), superseded_by = :id, "
                + "correction_reason = coalesce(:oldReason, correction_reason), updated_by = :actor "
                + "where tenant_id = :t and id = :oldId and superseded_at is null returning id) "
                + "insert into hr.salary_record (" + COLUMNS + ") select " + VALUES + " from s";
        int n = bind(db.jdbc().sql(sql), replacement).param("oldId", oldId).param("oldReason", oldReason).update();
        if (n == 0) {
            throw ApiException.conflict("stale-salary-record", "Salary record " + oldId
                    + " is no longer the current version; reload and retry");
        }
        insertAllowances(replacement);
    }

    public Optional<SalaryRow> find(UUID id) {
        return withAllowances(db.jdbc().sql("select s.* from hr.salary_record s where s.tenant_id = :t and s.id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", id).query(this::map).list())
                .stream().findFirst();
    }

    public Optional<Map<String, Object>> proposalPayload(SalaryRow row) {
        String reason = row.correctionReason();
        if (reason == null || !reason.startsWith(PROPOSAL_PREFIX) || row.supersededAt() == null
                || !row.supersededAt().equals(row.recordedAt())) {
            return Optional.empty();
        }
        return Optional.of(json.map(reason.substring(PROPOSAL_PREFIX.length())));
    }

    /** Current-knowledge versions of an employment, by valid_from. */
    public List<SalaryRow> line(UUID employmentId) {
        return withAllowances(db.jdbc().sql("""
                        select s.* from hr.salary_record s
                         where s.tenant_id = :t and s.employment_id = :e and s.superseded_at is null
                         order by s.valid_from""")
                .param("t", TenantContext.current().tenantId()).param("e", employmentId).query(this::map).list());
    }

    /** Rows matching an arbitrary filter on alias {@code s} (the daily job's window). */
    public List<SalaryRow> where(String condition, Map<String, ?> params) {
        return withAllowances(db.jdbc().sql("select s.* from hr.salary_record s where s.tenant_id = :t and "
                        + condition + " order by s.valid_from, s.recorded_at, s.id")
                .param("t", TenantContext.current().tenantId()).params(params).query(this::map).list());
    }

    private List<SalaryRow> withAllowances(List<SalaryRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<UUID, List<Allowance>> byRecord = new HashMap<>();
        db.jdbc().sql("""
                        select salary_record_id, component_code, amount, is_in_si_salary
                          from hr.salary_record_allowance where tenant_id = :t and salary_record_id in (:ids)
                         order by component_code""")
                .param("t", TenantContext.current().tenantId()).param("ids", rows.stream().map(SalaryRow::id).toList())
                .query((rs, n) -> byRecord.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new Allowance(rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4))))
                .list();
        List<SalaryRow> out = new ArrayList<>();
        for (SalaryRow r : rows) {
            out.add(new SalaryRow(r.id(), r.employmentId(), r.payBasis(), r.salaryKind(), r.baseSalaryAmount(),
                    r.agreedNetAmount(), r.netGuaranteedScope(), r.siSalaryAmount(), r.currencyCode(),
                    r.payScaleCode(), r.payScaleStepCode(), r.probationPct(), r.actionCode(), r.reasonCode(),
                    r.decisionId(), r.contractAppendixId(), r.validFrom(), r.validTo(), r.source(), r.recordedAt(),
                    r.supersededAt(), r.supersededBy(), r.correctionReason(), byRecord.getOrDefault(r.id(), List.of())));
        }
        return out;
    }

    private void insertAllowances(SalaryRow r) {
        for (Allowance a : r.allowances()) {
            db.jdbc().sql("""
                            insert into hr.salary_record_allowance (tenant_id, salary_record_id, component_code, amount,
                                   is_in_si_salary) values (:t, :id, :code, :amount, :si)""")
                    .param("t", TenantContext.current().tenantId()).param("id", r.id())
                    .param("code", a.componentCode()).param("amount", a.amount()).param("si", a.inSiSalary())
                    .update();
        }
    }

    public SalaryRow map(ResultSet rs, int n) throws SQLException {
        String scope = rs.getString("net_guaranteed_scope");
        List<String> scopeList = null;
        if (scope != null) {
            List<?> raw = json.read(scope, List.class);
            scopeList = raw.stream().map(Object::toString).toList();
        }
        return new SalaryRow(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getString("pay_basis"), rs.getString("salary_kind"), rs.getBigDecimal("base_salary_amount"),
                rs.getBigDecimal("agreed_net_amount"), scopeList, rs.getBigDecimal("si_salary_amount"),
                rs.getString("currency_code"), rs.getString("pay_scale_code"), rs.getString("pay_scale_step_code"),
                rs.getBigDecimal("probation_pct"), rs.getString("action_code"), rs.getString("reason_code"),
                rs.getObject("decision_id", UUID.class), rs.getObject("contract_appendix_id", UUID.class),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                rs.getString("source"), instant(rs, "recorded_at"), instant(rs, "superseded_at"),
                rs.getObject("superseded_by", UUID.class), rs.getString("correction_reason"), List.of());
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, SalaryRow r) {
        TenantContext ctx = TenantContext.current();
        return spec.param("t", ctx.tenantId()).param("id", r.id()).param("emp", r.employmentId())
                .param("basis", r.payBasis()).param("kind", r.salaryKind()).param("base", r.baseSalaryAmount())
                .param("net", r.agreedNetAmount())
                .param("scope", r.netGuaranteedScope() == null ? null : json.write(r.netGuaranteedScope()))
                .param("si", r.siSalaryAmount()).param("cur", r.currencyCode()).param("scale", r.payScaleCode())
                .param("step", r.payScaleStepCode()).param("prob", r.probationPct()).param("action", r.actionCode())
                .param("reason", r.reasonCode()).param("decision", r.decisionId())
                .param("appendix", r.contractAppendixId()).param("vf", r.validFrom()).param("vt", r.validTo())
                .param("source", r.source()).param("corr", r.correctionReason()).param("actor", ctx.userAccountId());
    }
}
