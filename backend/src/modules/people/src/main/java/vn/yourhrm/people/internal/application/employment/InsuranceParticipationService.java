package vn.yourhrm.people.internal.application.employment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.EmploymentEvents.InsuranceParticipationChanged;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.Row;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.legal.LegalParameters.Value;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Insurance participation per employment and type (BR-PPL-004, US-PPL-023 [high-uncertainty], R3-03;
 * {@code hr.insurance_participation}, Pattern A). The table stores the OUTCOME of the legal parameter
 * {@code insurance.multi_contract_rule} (type → FIRST_CONTRACT | HIGHEST_SALARY | EACH_EMPLOYER): the rule is never
 * coded here, switching HI to HIGHEST_SALARY is a parameter change. Every row is stamped with the pack it came from.
 *
 * <p>Derivation for a person from a date: the timeline is cut at every hire / end of a counted employment, every
 * salary version (when a type uses HIGHEST_SALARY) and every manual override; in each piece and type
 * <ul>
 *   <li>EACH_EMPLOYER: every live employment participates;</li>
 *   <li>FIRST_CONTRACT: the live employment hired first participates (tie: the one recorded first, i.e. smallest
 *       UUIDv7 id); the others do not;</li>
 *   <li>HIGHEST_SALARY: the live employment with the highest agreed base salary on the piece's first day participates
 *       (compared across legal entities inside the database through {@code hr.v_salary_record_masked}; no amount leaves
 *       it); tie or no salary record: the FIRST_CONTRACT order decides (deterministic tie rule);</li>
 *   <li>an HR override (with {@code override_reason}) fixes one employment × type for its period, including an
 *       exemption ({@code exemption_reason_code}); for a single-payer rule an overriding payer excludes the others.</li>
 * </ul>
 * Only (employment, type) lines whose outcome changed are rewritten (rows before the date are ended, later ones
 * cancelled) and each written / ended / cancelled row publishes {@code people.InsuranceParticipationChanged}
 * (OUT-37). Counted employments: not CANCELLED, and a PRE_HIRE only once its hire is approved.
 */
@Service
public class InsuranceParticipationService {

    /** A participation row as shown to HR / Payroll. */
    public record ParticipationView(UUID id, UUID employmentId, String insuranceType, LocalDate validFrom,
            LocalDate validTo, boolean participating, String basisRule, String exemptionReasonCode,
            boolean manualOverride, String overrideReason, UUID legalPackId, Long legalPackVersion, Instant decidedAt,
            UUID decidedBy, String status) {
    }

    /** HR override of one employment × type from {@code validFrom} ({@code validTo} exclusive, null = open). */
    public record OverrideRequest(String insuranceType, LocalDate validFrom, LocalDate validTo, boolean participating,
            String exemptionReasonCode, String overrideReason) {
    }

    /** One desired / existing piece of a line. */
    private record Piece(UUID employmentId, String type, LocalDate from, LocalDate to, boolean participating,
            String basis, String exemption, boolean override, String overrideReason, UUID packId, Long packVersion,
            UUID decidedBy) {

        boolean sameOutcome(Piece o) {
            return participating == o.participating && Objects.equals(basis, o.basis)
                    && Objects.equals(exemption, o.exemption) && override == o.override
                    && Objects.equals(overrideReason, o.overrideReason) && Objects.equals(packId, o.packId)
                    && Objects.equals(packVersion, o.packVersion);
        }

        boolean same(Piece o) {
            return sameOutcome(o) && from.equals(o.from) && Objects.equals(to, o.to);
        }

        boolean covers(LocalDate d) {
            return !d.isBefore(from) && (to == null || d.isBefore(to));
        }
    }

    private record Existing(UUID id, Piece piece, LocalDate originalFrom) {
    }

    private final HrDb db;
    private final EmploymentStore employments;
    private final EmployeeAccess access;
    private final PeopleLegalParameters legal;
    private final HrOutbox outbox;
    private final AuditLog audit;
    private final BusinessClock clock;

    public InsuranceParticipationService(HrDb db, EmploymentStore employments, EmployeeAccess access,
            PeopleLegalParameters legal, HrOutbox outbox, AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.employments = employments;
        this.access = access;
        this.legal = legal;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    // ------------------------------------------------------------------------------------------------------ reads

    /** Rows of the employment (ACTIVE; with {@code asOf} only those covering that day). */
    @HrTransactional(readOnly = true)
    public List<ParticipationView> list(UUID employmentId, LocalDate asOf) {
        access.require(PeoplePermissions.EMPLOYEE_VIEW, employmentId, asOf == null ? clock.today() : asOf);
        return rowsOfEmployment(employmentId, asOf);
    }

    List<ParticipationView> rowsOfEmployment(UUID employmentId, LocalDate asOf) {
        return db.jdbc().sql("""
                        select * from hr.insurance_participation
                         where tenant_id = :t and employment_id = :e and status = 'ACTIVE'
                           and (cast(:d as date) is null or valid_during @> cast(:d as date))
                         order by insurance_type, valid_from""")
                .param("t", tenant()).param("e", employmentId).param("d", asOf)
                .query((rs, n) -> new ParticipationView(rs.getObject("id", UUID.class),
                        rs.getObject("employment_id", UUID.class), rs.getString("insurance_type"),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                        rs.getBoolean("is_participating"), rs.getString("basis_rule"),
                        rs.getString("exemption_reason_code"), rs.getBoolean("is_manual_override"),
                        rs.getString("override_reason"), rs.getObject("legal_pack_id", UUID.class),
                        (Long) rs.getObject("legal_pack_version"),
                        rs.getTimestamp("decided_at").toInstant(), rs.getObject("decided_by", UUID.class),
                        rs.getString("status")))
                .list();
    }

    // ---------------------------------------------------------------------------------------------- HR actions

    /** HR re-runs the derivation of the employment's person from {@code from} (default: the employment's hire date). */
    @HrTransactional
    public List<ParticipationView> rederive(UUID employmentId, LocalDate from) {
        Row e = employments.find(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        LocalDate d = from == null ? e.hireDate() : from;
        access.require(PeoplePermissions.EMPLOYMENT_UPDATE, employmentId, d);
        derive(e.personId(), d, "HR_REDERIVE");
        return rowsOfEmployment(employmentId, null);
    }

    /** HR override (exemption or forced participation) with a reason; the rest of the person is re-derived. */
    @HrTransactional
    public List<ParticipationView> override(UUID employmentId, OverrideRequest o) {
        if (o.insuranceType() == null || !EmploymentKeys.INSURANCE_TYPES.contains(o.insuranceType())) {
            throw ApiException.unprocessable("invalid-insurance-type", "insuranceType must be one of "
                    + EmploymentKeys.INSURANCE_TYPES);
        }
        if (o.validFrom() == null) {
            throw ApiException.unprocessable("valid-from-required", "validFrom is required");
        }
        if (o.validTo() != null && !o.validTo().isAfter(o.validFrom())) {
            throw ApiException.unprocessable("invalid-period", "validTo (exclusive) must be after validFrom");
        }
        if (o.overrideReason() == null || o.overrideReason().isBlank()) {
            throw ApiException.unprocessable("override-reason-required", "An override needs a reason");
        }
        if (o.exemptionReasonCode() != null && o.participating()) {
            throw ApiException.unprocessable("exempt-cannot-participate", "An exempt employment does not participate");
        }
        access.require(PeoplePermissions.EMPLOYMENT_UPDATE, employmentId, o.validFrom());
        Row e = employments.find(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!e.covers(o.validFrom())) {
            throw ApiException.unprocessable("outside-employment", "The override starts outside the employment");
        }
        Piece p = new Piece(e.id(), o.insuranceType(), o.validFrom(), o.validTo(), o.participating(), null,
                o.exemptionReasonCode(), true, o.overrideReason(), null, null,
                TenantContext.current().userAccountId());
        derive(e.personId(), o.validFrom(), "HR_OVERRIDE", p);
        audit.record(new BusinessAction("OVERRIDE_INSURANCE_PARTICIPATION", "hr", "employment", e.id(),
                e.legalEntityId(), PrimaryEmploymentService.details("insuranceType", o.insuranceType(),
                        "validFrom", o.validFrom(), "validTo", o.validTo(), "participating", o.participating(),
                        "exemption", o.exemptionReasonCode(), "reason", o.overrideReason())));
        return rowsOfEmployment(employmentId, null);
    }

    // -------------------------------------------------------------------------------------- derivation (internal)

    /**
     * Re-derives the participation of {@code personId} from {@code from}; runs in the caller's People transaction, no
     * permission check (callers: hire approval / activation / cancellation, employment end, salary change, legal
     * pack, HR actions). Missing rule ⇒ 422 {@code legal-parameter-missing} (fail closed).
     *
     * @return the number of rows written, ended or cancelled
     */
    @HrTransactional
    public int derive(UUID personId, LocalDate from, String trigger) {
        return derive(personId, from, trigger, null);
    }

    private int derive(UUID personId, LocalDate from, String trigger, Piece extraOverride) {
        employments.lockPerson(personId);
        List<Row> counted = employments.ofPerson(personId).stream().filter(this::counted).toList();
        List<Existing> existing = existing(personId, from);
        List<Piece> overrides = new ArrayList<>();
        if (extraOverride != null) {
            overrides.add(extraOverride);
        }
        existing.stream().map(Existing::piece).filter(Piece::override).forEach(overrides::add);

        TreeSet<LocalDate> cuts = new TreeSet<>();
        cuts.add(from);
        for (Row e : counted) {
            addCut(cuts, from, e.hireDate());
            addCut(cuts, from, e.endExclusive());
        }
        for (Piece o : overrides) {
            addCut(cuts, from, o.from());
            addCut(cuts, from, o.to());
        }
        boolean anyHighest = false;
        List<LocalDate> cutList = new ArrayList<>(cuts);
        Map<LocalDate, Value> rules = new LinkedHashMap<>();
        for (LocalDate c : cutList) {
            Value v = ruleValue(c);
            rules.put(c, v);
            anyHighest |= v.asMap().containsValue("HIGHEST_SALARY");
        }
        if (anyHighest && !counted.isEmpty()) {
            salaryCuts(counted, from).forEach(c -> addCut(cuts, from, c));
            cutList = new ArrayList<>(cuts);
            for (LocalDate c : cutList) {
                rules.computeIfAbsent(c, this::ruleValue);
            }
        }

        List<Piece> desired = new ArrayList<>();
        for (int i = 0; i < cutList.size(); i++) {
            LocalDate start = cutList.get(i);
            LocalDate end = i + 1 < cutList.size() ? cutList.get(i + 1) : null;
            List<Row> live = counted.stream().filter(e -> e.covers(start)).toList();
            if (live.isEmpty()) {
                continue;
            }
            Value rule = rules.get(start);
            List<UUID> bySalary = null;
            for (String type : EmploymentKeys.INSURANCE_TYPES) {
                String r = ruleFor(rule, type);
                if ("HIGHEST_SALARY".equals(r) && bySalary == null) {
                    bySalary = rankBySalary(live, start);
                }
                desired.addAll(piecesFor(type, r, rule, live, start, end, overrides, bySalary));
            }
        }
        List<Piece> merged = merge(desired);
        return apply(personId, from, existing, merged, trigger);
    }

    /** Hook for the separation stream / daily job: the employment ended (its end date is set). */
    @HrTransactional
    public int onEmploymentEnded(UUID employmentId) {
        Row e = employments.find(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (e.endDate() == null) {
            return 0;
        }
        return derive(e.personId(), e.endExclusive(), "EMPLOYMENT_ENDED", null);
    }

    /** Persons with participation rows running beyond the end of their employment (daily job). */
    @HrTransactional
    public int rederiveEnded() {
        List<UUID> ids = db.jdbc().sql("""
                        select distinct e.id from hr.insurance_participation p
                          join hr.employment e on e.tenant_id = p.tenant_id and e.id = p.employment_id
                         where p.tenant_id = :t and p.status = 'ACTIVE'
                           and ((e.end_date is not null and (p.valid_to is null or p.valid_to > e.end_date + 1))
                                or e.status = 'CANCELLED')""")
                .param("t", tenant()).query(UUID.class).list();
        int n = 0;
        for (UUID id : ids) {
            Row e = employments.find(id).orElseThrow();
            LocalDate from = "CANCELLED".equals(e.status()) ? e.hireDate() : e.endExclusive();
            if (derive(e.personId(), from, "EMPLOYMENT_ENDED", null) > 0) {
                n++;
            }
        }
        return n;
    }

    /** Re-derives every person with a counted employment live on or after {@code from} (new legal pack). */
    @HrTransactional
    public int rederiveAll(LocalDate from, String trigger) {
        List<UUID> persons = db.jdbc().sql("""
                        select distinct person_id from hr.employment
                         where tenant_id = :t and status <> 'CANCELLED' and (end_date is null or end_date >= :d)""")
                .param("t", tenant()).param("d", from).query(UUID.class).list();
        int n = 0;
        for (UUID p : persons) {
            if (derive(p, from, trigger, null) > 0) {
                n++;
            }
        }
        return n;
    }

    /** Does any insurance type use HIGHEST_SALARY on {@code d}? False when the rule is not defined. */
    boolean usesHighestSalary(LocalDate d) {
        return legal.find(EmploymentKeys.INSURANCE_MULTI_CONTRACT_RULE, Scope.tenant(), d)
                .map(v -> v.value() instanceof Map<?, ?> m && m.containsValue("HIGHEST_SALARY")).orElse(false);
    }

    // ---------------------------------------------------------------------------------------------------------

    private boolean counted(Row e) {
        if ("CANCELLED".equals(e.status())) {
            return false;
        }
        if (!"PRE_HIRE".equals(e.status()) || !"APP".equals(e.source())) {
            return true;
        }
        return HireDecisions.approved(db, e.id());
    }

    private Value ruleValue(LocalDate d) {
        Value v = legal.require(EmploymentKeys.INSURANCE_MULTI_CONTRACT_RULE, Scope.tenant(), d);
        if (!(v.value() instanceof Map<?, ?>)) {
            throw ApiException.unprocessable("legal-parameter-invalid", "Legal parameter "
                    + EmploymentKeys.INSURANCE_MULTI_CONTRACT_RULE + " must map insurance type to rule");
        }
        return v;
    }

    private static String ruleFor(Value rule, String type) {
        Object r = rule.asMap().get(type);
        if (r == null || !EmploymentKeys.RULES.contains(r.toString())) {
            throw ApiException.unprocessable("legal-parameter-missing", "Legal parameter "
                    + EmploymentKeys.INSURANCE_MULTI_CONTRACT_RULE + " has no valid rule for " + type,
                    Map.of("parameter", EmploymentKeys.INSURANCE_MULTI_CONTRACT_RULE, "insuranceType", type));
        }
        return r.toString();
    }

    private List<Piece> piecesFor(String type, String rule, Value v, List<Row> live, LocalDate start, LocalDate end,
            List<Piece> overrides, List<UUID> bySalary) {
        Map<UUID, Piece> forced = new LinkedHashMap<>();
        for (Row e : live) {
            overrides.stream().filter(o -> o.employmentId().equals(e.id()) && o.type().equals(type) && o.covers(start))
                    .findFirst().ifPresent(o -> forced.put(e.id(), o));
        }
        List<Piece> out = new ArrayList<>();
        UUID payer = null;
        if (!"EACH_EMPLOYER".equals(rule)) {
            payer = forced.values().stream().filter(Piece::participating).map(Piece::employmentId).findFirst()
                    .orElse(null);
            if (payer == null) {
                List<Row> candidates = live.stream().filter(e -> !forced.containsKey(e.id())).toList();
                if ("HIGHEST_SALARY".equals(rule) && bySalary != null) {
                    payer = bySalary.stream().filter(id -> candidates.stream().anyMatch(c -> c.id().equals(id)))
                            .findFirst().orElse(null);
                } else {
                    payer = candidates.stream().min(FIRST_CONTRACT).map(Row::id).orElse(null);
                }
            }
        }
        for (Row e : live) {
            Piece o = forced.get(e.id());
            if (o != null) {
                String basis = o.exemption() != null ? null : rule;
                out.add(new Piece(e.id(), type, start, end, o.participating(), basis, o.exemption(), true,
                        o.overrideReason(), v.packId(), v.packVersion(), o.decidedBy()));
                continue;
            }
            boolean participates = "EACH_EMPLOYER".equals(rule) || e.id().equals(payer);
            out.add(new Piece(e.id(), type, start, end, participates, rule, null, false, null, v.packId(),
                    v.packVersion(), null));
        }
        return out;
    }

    /** FIRST_CONTRACT order and the HIGHEST_SALARY tie rule: earliest hire date, then the row recorded first. */
    static final Comparator<Row> FIRST_CONTRACT = Comparator.comparing(Row::hireDate).thenComparing(Row::id);

    /**
     * Employment ids ordered by agreed base salary on {@code d} (highest first; ties and employments without a salary
     * by the FIRST_CONTRACT order). Amounts are compared inside the database via the masking view at the PII level
     * (system comparison, no amount leaves the database; the previous level is restored).
     */
    private List<UUID> rankBySalary(List<Row> live, LocalDate d) {
        String before = db.jdbc().sql("select coalesce(current_setting('app.mask_level', true), '')")
                .query(String.class).single();
        db.jdbc().sql("select set_config('app.mask_level', 'PII', true)").query((rs, n) -> 1).single();
        try {
            // groups of equal amounts, highest first; the amounts themselves are not selected
            List<List<UUID>> groups = db.jdbc().sql("""
                            select array_agg(s.employment_id) from hr.v_salary_record_masked s
                             where s.tenant_id = :t and s.employment_id in (:ids) and s.superseded_at is null
                               and s.valid_from <= :d and (s.valid_to is null or s.valid_to > :d)
                             group by s.base_salary_amount order by s.base_salary_amount desc nulls last""")
                    .param("t", tenant()).param("ids", live.stream().map(Row::id).toList()).param("d", d)
                    .query((rs, n) -> List.of((UUID[]) rs.getArray(1).getArray())).list();
            List<UUID> out = new ArrayList<>();
            for (List<UUID> g : groups) {
                live.stream().filter(e -> g.contains(e.id())).sorted(FIRST_CONTRACT).map(Row::id).forEach(out::add);
            }
            live.stream().sorted(FIRST_CONTRACT).map(Row::id).filter(id -> !out.contains(id)).forEach(out::add);
            return out;
        } finally {
            db.jdbc().sql("select set_config('app.mask_level', :l, true)").param("l", before)
                    .query((rs, n) -> 1).single();
        }
    }

    private List<LocalDate> salaryCuts(List<Row> counted, LocalDate from) {
        return db.jdbc().sql("""
                        select distinct s.valid_from from hr.salary_record s
                         where s.tenant_id = :t and s.employment_id in (:ids) and s.superseded_at is null
                           and s.valid_from > :d""")
                .param("t", tenant()).param("ids", counted.stream().map(Row::id).toList()).param("d", from)
                .query(LocalDate.class).list();
    }

    private static void addCut(Set<LocalDate> cuts, LocalDate from, LocalDate d) {
        if (d != null && d.isAfter(from)) {
            cuts.add(d);
        }
    }

    private static List<Piece> merge(List<Piece> pieces) {
        Map<String, List<Piece>> byLine = new LinkedHashMap<>();
        for (Piece p : pieces) {
            byLine.computeIfAbsent(p.employmentId() + "|" + p.type(), k -> new ArrayList<>()).add(p);
        }
        List<Piece> out = new ArrayList<>();
        for (List<Piece> line : byLine.values()) {
            line.sort(Comparator.comparing(Piece::from));
            Piece cur = null;
            for (Piece p : line) {
                if (cur != null && cur.to() != null && cur.to().equals(p.from()) && cur.sameOutcome(p)) {
                    cur = new Piece(cur.employmentId(), cur.type(), cur.from(), p.to(), cur.participating(),
                            cur.basis(), cur.exemption(), cur.override(), cur.overrideReason(), cur.packId(),
                            cur.packVersion(), cur.decidedBy());
                } else {
                    if (cur != null) {
                        out.add(cur);
                    }
                    cur = p;
                }
            }
            if (cur != null) {
                out.add(cur);
            }
        }
        return out;
    }

    /** ACTIVE rows of the person ending after {@code from}, clipped to start no earlier than it. */
    private List<Existing> existing(UUID personId, LocalDate from) {
        return db.jdbc().sql("""
                        select * from hr.insurance_participation
                         where tenant_id = :t and person_id = :p and status = 'ACTIVE'
                           and (valid_to is null or valid_to > :d)
                         order by employment_id, insurance_type, valid_from""")
                .param("t", tenant()).param("p", personId).param("d", from)
                .query((rs, n) -> {
                    LocalDate vf = rs.getObject("valid_from", LocalDate.class);
                    return new Existing(rs.getObject("id", UUID.class), new Piece(
                            rs.getObject("employment_id", UUID.class), rs.getString("insurance_type"),
                            vf.isBefore(from) ? from : vf, rs.getObject("valid_to", LocalDate.class),
                            rs.getBoolean("is_participating"), rs.getString("basis_rule"),
                            rs.getString("exemption_reason_code"), rs.getBoolean("is_manual_override"),
                            rs.getString("override_reason"), rs.getObject("legal_pack_id", UUID.class),
                            (Long) rs.getObject("legal_pack_version"), rs.getObject("decided_by", UUID.class)), vf);
                })
                .list();
    }

    private int apply(UUID personId, LocalDate from, List<Existing> existing, List<Piece> desired, String trigger) {
        Set<String> keys = new LinkedHashSet<>();
        existing.forEach(x -> keys.add(x.piece().employmentId() + "|" + x.piece().type()));
        desired.forEach(p -> keys.add(p.employmentId() + "|" + p.type()));
        List<Existing> remove = new ArrayList<>();
        List<Piece> insert = new ArrayList<>();
        for (String key : keys) {
            List<Existing> old = existing.stream()
                    .filter(x -> key.equals(x.piece().employmentId() + "|" + x.piece().type())).toList();
            List<Piece> now = desired.stream().filter(p -> key.equals(p.employmentId() + "|" + p.type())).toList();
            boolean same = old.size() == now.size();
            for (int i = 0; same && i < old.size(); i++) {
                same = old.get(i).piece().same(now.get(i));
            }
            if (!same) {
                remove.addAll(old);
                insert.addAll(now);
            }
        }
        int n = 0;
        UUID actor = TenantContext.current().userAccountId();
        for (Existing x : remove) {
            Row e = employments.find(x.piece().employmentId()).orElseThrow();
            if (x.originalFrom().isBefore(from)) {
                db.jdbc().sql("""
                                update hr.insurance_participation set valid_to = :vt, version = version + 1,
                                       updated_at = now(), updated_by = :a
                                 where tenant_id = :t and id = :id""")
                        .param("vt", from).param("a", actor).param("t", tenant()).param("id", x.id()).update();
                publish(e, x.id(), x.piece(), x.originalFrom(), from, "ACTIVE", trigger);
            } else {
                db.jdbc().sql("""
                                update hr.insurance_participation set status = 'CANCELLED', version = version + 1,
                                       updated_at = now(), updated_by = :a
                                 where tenant_id = :t and id = :id""")
                        .param("a", actor).param("t", tenant()).param("id", x.id()).update();
                publish(e, x.id(), x.piece(), x.piece().from(), x.piece().to(), "CANCELLED", trigger);
            }
            n++;
        }
        for (Piece p : insert) {
            Row e = employments.find(p.employmentId()).orElseThrow();
            UUID id = Ids.newId();
            try {
                db.jdbc().sql("""
                                insert into hr.insurance_participation (tenant_id, id, person_id, employment_id,
                                       insurance_type, valid_from, valid_to, is_participating, basis_rule,
                                       exemption_reason_code, legal_pack_id, legal_pack_version, decided_by,
                                       is_manual_override, override_reason, source, created_by, updated_by)
                                values (:t, :id, :p, :e, :type, :vf, :vt, :part, :basis, :ex, :pack, :pv, :by, :ov,
                                        :reason, 'APP', :a, :a)""")
                        .param("t", tenant()).param("id", id).param("p", personId).param("e", p.employmentId())
                        .param("type", p.type()).param("vf", p.from()).param("vt", p.to())
                        .param("part", p.participating()).param("basis", p.basis()).param("ex", p.exemption())
                        .param("pack", p.packId()).param("pv", p.packVersion()).param("by", p.decidedBy())
                        .param("ov", p.override()).param("reason", p.overrideReason()).param("a", actor).update();
            } catch (DataIntegrityViolationException ex) {
                throw ApiException.unprocessable("insurance-participation-conflict", "Two employments of the person "
                        + "would both participate in " + p.type() + " under a single-payer rule from " + p.from()
                        + " (insurance_participation_single_ex)");
            }
            publish(e, id, p, p.from(), p.to(), "ACTIVE", trigger);
            n++;
        }
        return n;
    }

    private void publish(Row e, UUID id, Piece p, LocalDate from, LocalDate to, String status, String trigger) {
        outbox.publish(new InsuranceParticipationChanged(tenant(), e.legalEntityId(), e.personId(), e.id(), from, id,
                p.type(), p.participating(), p.basis(), p.exemption(), p.override(), p.packId(), p.packVersion(),
                from, to, status, trigger));
    }

    /** For the report: is the employment's line of {@code type} covered on {@code d}? */
    Optional<ParticipationView> on(UUID employmentId, String type, LocalDate d) {
        return rowsOfEmployment(employmentId, d).stream().filter(r -> r.insuranceType().equals(type)).findFirst();
    }
}
