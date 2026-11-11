package vn.yourhrm.people.internal.application.integration;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.EmployeeDirectory;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.HrMasking;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.MaskLevel;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * OUT-03 read API ({@link EmployeeDirectory}, US-PPL-081, NFR-PPL-001). Set-based: one page costs five queries
 * whatever its size (employments + masked person + scope flags, assignments, contracts, salary versions,
 * allowances), so a payroll run never issues per-employee queries. Scope: {@code hr.employee.view} filters the
 * employments; {@code hr.compensation.view} (evaluated per employment in the same query) and the caller's pay masking
 * level decide whether the salary summary is filled. Salary amounts are read only through
 * {@code hr.v_salary_record_masked} / {@code hr.v_salary_record_allowance_masked}; each read that returns amounts is
 * audited (BR-PPL-044).
 */
@Service
public class EmployeeSnapshotService implements EmployeeDirectory {

    private final HrDb db;
    private final EmployeeAccess access;
    private final HrMasking masking;
    private final AuditLog audit;
    private final BusinessClock clock;

    public EmployeeSnapshotService(HrDb db, EmployeeAccess access, HrMasking masking, AuditLog audit,
            BusinessClock clock) {
        this.db = db;
        this.access = access;
        this.masking = masking;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @HrTransactional
    public Optional<EmployeeSnapshot> snapshot(UUID employmentId, LocalDate asOf, Instant asKnownAt) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        List<EmployeeSnapshot> out = load(d, asKnownAt, "e.id = :id", Map.of("id", employmentId), 1, 0);
        out.stream().filter(EmployeeSnapshot::salaryVisible).filter(s -> s.salary() != null).findFirst()
                .ifPresent(s -> audit.record(new BusinessAction("VIEW_SALARY", "hr", "employment", employmentId,
                        s.employment().legalEntityId(), details(d, asKnownAt, 1))));
        return out.stream().findFirst();
    }

    @Override
    @HrTransactional
    public List<EmployeeSnapshot> snapshots(UUID legalEntityId, LocalDate asOf, Instant asKnownAt, int limit,
            int offset) {
        if (legalEntityId == null) {
            throw ApiException.unprocessable("legal-entity-required", "legalEntityId is required");
        }
        LocalDate d = asOf == null ? clock.today() : asOf;
        List<EmployeeSnapshot> out = load(d, asKnownAt, """
                e.legal_entity_id = :le and e.status <> 'CANCELLED' and e.hire_date <= :asOf
                and (e.end_date is null or e.end_date >= :asOf)""", Map.of("le", legalEntityId),
                Math.min(Math.max(limit, 1), MAX_BATCH), Math.max(offset, 0));
        long withSalary = out.stream().filter(s -> s.salary() != null).count();
        if (withSalary > 0) {
            audit.record(new BusinessAction("VIEW_SALARY_BATCH", "hr", "legal_entity", legalEntityId, legalEntityId,
                    details(d, asKnownAt, withSalary)));
        }
        return out;
    }

    private static Map<String, String> details(LocalDate asOf, Instant asKnownAt, long count) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("purpose", "READ_API_OUT_03");
        m.put("asOf", asOf.toString());
        if (asKnownAt != null) {
            m.put("asKnownAt", asKnownAt.toString());
        }
        m.put("salaryRecords", Long.toString(count));
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------

    private record Head(PersonSummary person, EmploymentSummary employment, boolean compensationScope) {
    }

    private List<EmployeeSnapshot> load(LocalDate asOf, Instant asKnownAt, String filter, Map<String, Object> params,
            int limit, int offset) {
        UUID tenant = TenantContext.current().tenantId();
        masking.apply(PeoplePermissions.EMPLOYEE_VIEW);
        SqlFragment view = access.scope(PeoplePermissions.EMPLOYEE_VIEW, asOf, "v");
        SqlFragment comp = access.scope(PeoplePermissions.COMPENSATION_VIEW, asOf, "c");
        var spec = db.jdbc().sql("select e.*, p.family_name, p.middle_name, p.given_name, p.full_name, "
                        + "p.preferred_name, p.birth_date as p_birth_date, p.gender_code, p.nationality_code, "
                        + "p.national_id_type, p.national_id_masked, p.tax_code_masked, p.si_number_masked, "
                        + "(select pe.employment_id from hr.primary_employment pe where pe.tenant_id = e.tenant_id "
                        + "   and pe.person_id = e.person_id and pe.status = 'ACTIVE' and pe.valid_from <= :asOf "
                        + "   and (pe.valid_to is null or pe.valid_to > :asOf) limit 1) as pit_primary, "
                        + "(" + comp.sql() + ") as comp_scope from " + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " join hr.v_person_masked p on p.tenant_id = e.tenant_id and p.id = e.person_id"
                        + " where e.tenant_id = :t and " + filter + " and " + view.sql()
                        + " order by e.employee_no limit :limit offset :offset")
                .param("t", tenant).param("asOf", asOf).param("limit", limit).param("offset", offset)
                .params(view.params()).params(comp.params());
        for (var p : params.entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        List<Head> heads = spec.query((rs, n) -> head(rs, asOf)).list();
        if (heads.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = heads.stream().map(h -> h.employment().employmentId()).toList();
        Map<UUID, List<AssignmentSummary>> assignments = assignments(tenant, ids, asOf, asKnownAt);
        Map<UUID, ContractSummary> contracts = contracts(tenant, ids, asOf);
        List<UUID> payIds = heads.stream().filter(Head::compensationScope).map(h -> h.employment().employmentId())
                .toList();
        Map<UUID, SalarySummary> salaries = Map.of();
        boolean showsPay = false;
        if (!payIds.isEmpty()) {
            MaskLevel level = masking.apply(PeoplePermissions.COMPENSATION_VIEW);
            showsPay = level.showsPay();
            if (showsPay) {
                salaries = salaries(tenant, payIds, asOf, asKnownAt);
            }
        }
        List<EmployeeSnapshot> out = new ArrayList<>();
        for (Head h : heads) {
            UUID id = h.employment().employmentId();
            List<AssignmentSummary> lines = assignments.getOrDefault(id, List.of());
            AssignmentSummary primary = lines.stream().filter(a -> "PRIMARY".equals(a.assignmentType())).findFirst()
                    .orElse(null);
            boolean visible = h.compensationScope() && showsPay;
            out.add(new EmployeeSnapshot(asOf, asKnownAt, h.person(), h.employment(), primary, lines,
                    contracts.get(id), visible, visible ? salaries.get(id) : null));
        }
        return out;
    }

    private static Head head(ResultSet rs, LocalDate asOf) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        LocalDate hire = rs.getObject("hire_date", LocalDate.class);
        LocalDate end = rs.getObject("end_date", LocalDate.class);
        String status = rs.getString("status");
        boolean active = !"CANCELLED".equals(status) && !asOf.isBefore(hire) && (end == null || !asOf.isAfter(end));
        PersonSummary p = new PersonSummary(rs.getObject("person_id", UUID.class), rs.getString("family_name"),
                rs.getString("middle_name"), rs.getString("given_name"), rs.getString("full_name"),
                rs.getString("preferred_name"), rs.getObject("p_birth_date", LocalDate.class),
                rs.getString("gender_code"), rs.getString("nationality_code"), rs.getString("national_id_type"),
                rs.getString("national_id_masked"), rs.getString("tax_code_masked"), rs.getString("si_number_masked"));
        EmploymentSummary e = new EmploymentSummary(id, p.personId(), rs.getObject("legal_entity_id", UUID.class),
                rs.getString("employee_no"), status, active, rs.getString("hire_type"), hire,
                rs.getObject("original_hire_date", LocalDate.class), rs.getObject("seniority_date", LocalDate.class),
                rs.getObject("probation_end_date", LocalDate.class), end,
                rs.getObject("last_working_date", LocalDate.class), rs.getString("worker_category_code"),
                rs.getString("employee_category_code"), rs.getString("working_regime_code"),
                rs.getObject("pit_primary", UUID.class));
        return new Head(p, e, rs.getBoolean("comp_scope"));
    }

    private static String known(String alias, Instant asKnownAt) {
        return asKnownAt == null ? alias + ".superseded_at is null"
                : alias + ".recorded_at <= :k and (" + alias + ".superseded_at is null or " + alias
                        + ".superseded_at > :k)";
    }

    private static OffsetDateTime at(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private Map<UUID, List<AssignmentSummary>> assignments(UUID tenant, List<UUID> ids, LocalDate asOf,
            Instant asKnownAt) {
        var spec = db.jdbc().sql("""
                        select a.id, a.assignment_key, a.employment_id, a.assignment_type, a.org_unit_id, a.position_id,
                               a.job_title_id, a.grade_id, a.manager_employment_id, a.location_id, a.cost_centre_id,
                               a.fte, a.valid_from, a.valid_to, a.recorded_at
                          from hr.assignment a
                         where a.tenant_id = :t and a.employment_id in (:ids) and a.valid_during @> cast(:asOf as date)
                           and""" + " " + known("a", asKnownAt) + " order by a.employment_id, a.assignment_type, a.valid_from")
                .param("t", tenant).param("ids", ids).param("asOf", asOf);
        if (asKnownAt != null) {
            spec = spec.param("k", at(asKnownAt));
        }
        Map<UUID, List<AssignmentSummary>> out = new HashMap<>();
        spec.query(rs -> {
            out.computeIfAbsent(rs.getObject("employment_id", UUID.class), k -> new ArrayList<>())
                    .add(new AssignmentSummary(rs.getObject("id", UUID.class),
                            rs.getObject("assignment_key", UUID.class), rs.getString("assignment_type"),
                            rs.getObject("org_unit_id", UUID.class), rs.getObject("position_id", UUID.class),
                            rs.getObject("job_title_id", UUID.class), rs.getObject("grade_id", UUID.class),
                            rs.getObject("manager_employment_id", UUID.class), rs.getObject("location_id", UUID.class),
                            rs.getObject("cost_centre_id", UUID.class), rs.getBigDecimal("fte"),
                            rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                            instant(rs.getTimestamp("recorded_at"))));
        });
        return out;
    }

    private Map<UUID, ContractSummary> contracts(UUID tenant, List<UUID> ids, LocalDate asOf) {
        Map<UUID, ContractSummary> out = new HashMap<>();
        db.jdbc().sql("""
                        select distinct on (c.employment_id) c.employment_id, c.id, c.contract_no, c.contract_type_code,
                               c.definite_seq, c.start_date, c.end_date, c.sign_date, c.status
                          from hr.contract c
                         where c.tenant_id = :t and c.employment_id in (:ids)
                           and c.status in ('ACTIVE','SUSPENDED','EXPIRED','TERMINATED')
                           and c.contract_period @> cast(:asOf as date)
                         order by c.employment_id, c.start_date desc""")
                .param("t", tenant).param("ids", ids).param("asOf", asOf)
                .query(rs -> {
                    out.put(rs.getObject("employment_id", UUID.class), new ContractSummary(rs.getObject("id", UUID.class),
                            rs.getString("contract_no"), rs.getString("contract_type_code"),
                            rs.getObject("definite_seq", Integer.class), rs.getObject("start_date", LocalDate.class),
                            rs.getObject("end_date", LocalDate.class), rs.getObject("sign_date", LocalDate.class),
                            rs.getString("status")));
                });
        return out;
    }

    /** Reads amounts through the masking views; the caller has applied the compensation mask level. */
    private Map<UUID, SalarySummary> salaries(UUID tenant, List<UUID> ids, LocalDate asOf, Instant asKnownAt) {
        record Row(UUID employmentId, SalarySummary summary) {
        }
        var spec = db.jdbc().sql("""
                        select s.id, s.employment_id, s.pay_basis, s.salary_kind, s.base_salary_amount,
                               s.agreed_net_amount,
                               (select array_agg(x) from jsonb_array_elements_text(s.net_guaranteed_scope) x)
                                   as net_guaranteed_scope, s.si_salary_amount, s.currency_code,
                               s.pay_scale_code, s.pay_scale_step_code, r.probation_pct, s.valid_from, s.valid_to,
                               s.recorded_at
                          from hr.v_salary_record_masked s
                          join hr.salary_record r on r.tenant_id = s.tenant_id and r.id = s.id
                         where s.tenant_id = :t and s.employment_id in (:ids) and s.valid_from <= :asOf
                           and (s.valid_to is null or s.valid_to > :asOf) and""" + " " + known("s", asKnownAt))
                .param("t", tenant).param("ids", ids).param("asOf", asOf);
        if (asKnownAt != null) {
            spec = spec.param("k", at(asKnownAt));
        }
        List<Row> rows = spec.query((rs, n) -> new Row(rs.getObject("employment_id", UUID.class),
                new SalarySummary(rs.getObject("id", UUID.class), rs.getString("pay_basis"),
                        rs.getString("salary_kind"), rs.getBigDecimal("base_salary_amount"),
                        rs.getBigDecimal("agreed_net_amount"), strings(rs.getArray("net_guaranteed_scope")),
                        rs.getBigDecimal("si_salary_amount"), rs.getString("currency_code"),
                        rs.getString("pay_scale_code"), rs.getString("pay_scale_step_code"),
                        rs.getBigDecimal("probation_pct"), List.of(), rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class), instant(rs.getTimestamp("recorded_at"))))).list();
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Allowance>> allowances = new HashMap<>();
        db.jdbc().sql("""
                        select salary_record_id, component_code, amount, is_in_si_salary
                          from hr.v_salary_record_allowance_masked
                         where tenant_id = :t and salary_record_id in (:ids) order by component_code""")
                .param("t", tenant).param("ids", rows.stream().map(r -> r.summary().salaryRecordId()).toList())
                .query(rs -> {
                    allowances.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Allowance(rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4)));
                });
        Map<UUID, SalarySummary> out = new HashMap<>();
        for (Row r : rows) {
            SalarySummary s = r.summary();
            out.put(r.employmentId(), new SalarySummary(s.salaryRecordId(), s.payBasis(), s.salaryKind(),
                    s.baseSalaryAmount(), s.agreedNetAmount(), s.netGuaranteedScope(), s.siSalaryAmount(),
                    s.currencyCode(), s.payScaleCode(), s.payScaleStepCode(), s.probationPct(),
                    List.copyOf(allowances.getOrDefault(s.salaryRecordId(), List.of())), s.validFrom(), s.validTo(),
                    s.recordedAt()));
        }
        return out;
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return null;
        }
        Object v = a.getArray();
        return v instanceof Object[] arr ? Arrays.stream(arr).map(String::valueOf).toList() : null;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
