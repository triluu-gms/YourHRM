package vn.yourhrm.people.internal.application.person;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.HrMasking;
import vn.yourhrm.people.internal.application.person.PersonService.PersonView;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Employee list and profile (US-PPL-015/016, NFR-PPL-003/009):
 * <ul>
 *   <li>list: one row per employment in the caller's scope as of a date; accent-insensitive name search on the
 *       generated {@code full_name_search} (trigram index) or employee-number prefix; filters legal entity, org unit,
 *       status, worker / employee category; Vietnamese collation; masked (P3 as last 4 only);</li>
 *   <li>profile: the masked person, the person's employments in scope, the current primary-assignment summary and
 *       links to the tabs the caller may open (tabs owned by other streams are their endpoints; hidden without
 *       permission).</li>
 * </ul>
 */
@Service
public class EmployeeDirectoryService {

    public static final Set<String> STATUSES = Set.of("PRE_HIRE", "ACTIVE", "SUSPENDED", "TERMINATED", "CANCELLED");
    private static final int MAX_SIZE = 200;

    public record Query(String q, UUID legalEntityId, UUID orgUnitId, String status, String workerCategoryCode,
            String employeeCategoryCode, LocalDate asOf, Integer page, Integer size) {
    }

    public record EmployeeRow(UUID employmentId, UUID personId, String employeeNo, String fullName,
            String nationalIdMasked, UUID legalEntityId, UUID orgUnitId, UUID positionId, UUID jobTitleId,
            UUID locationId, String status, LocalDate hireDate, LocalDate endDate, String workerCategoryCode,
            String employeeCategoryCode) {
    }

    public record Page(List<EmployeeRow> items, long total, int page, int size) {
    }

    public record EmploymentSummary(UUID employmentId, String employeeNo, UUID legalEntityId, String hireType,
            String status, LocalDate hireDate, LocalDate endDate, LocalDate seniorityDate, String workerCategoryCode,
            String employeeCategoryCode) {
    }

    public record AssignmentSummary(UUID assignmentId, String assignmentType, UUID legalEntityId, UUID orgUnitId,
            UUID positionId, UUID jobTitleId, UUID gradeId, UUID locationId, UUID managerEmploymentId,
            String managerName, java.math.BigDecimal fte, LocalDate validFrom, LocalDate validTo) {
    }

    public record Profile(EmploymentSummary employment, PersonView person, List<EmploymentSummary> otherEmployments,
            AssignmentSummary primaryAssignment, Map<String, String> tabs, LocalDate asOf) {
    }

    private final HrDb db;
    private final PersonSupport support;
    private final EmployeeAccess access;
    private final HrMasking masking;
    private final PersonService persons;

    public EmployeeDirectoryService(HrDb db, PersonSupport support, EmployeeAccess access, HrMasking masking,
            PersonService persons) {
        this.db = db;
        this.support = support;
        this.access = access;
        this.masking = masking;
        this.persons = persons;
    }

    @HrTransactional(readOnly = true)
    public Page list(Query q) {
        LocalDate asOf = support.orToday(q.asOf());
        int size = q.size() == null ? 50 : q.size();
        int page = q.page() == null ? 0 : q.page();
        if (size < 1 || size > MAX_SIZE || page < 0) {
            throw ApiException.unprocessable("invalid-page", "size must be 1.." + MAX_SIZE + " and page >= 0");
        }
        if (q.status() != null && !STATUSES.contains(q.status())) {
            throw ApiException.unprocessable("invalid-status", "Status must be one of " + STATUSES);
        }
        SqlFragment scope = access.scope(PeoplePermissions.EMPLOYEE_VIEW, asOf, "s");
        String search = q.q() == null || q.q().isBlank() ? null : q.q().trim();
        List<Long> total = new ArrayList<>();
        List<EmployeeRow> rows = db.jdbc().sql("select e.id, e.person_id, e.employee_no, e.legal_entity_id, e.status,"
                        + " e.hire_date, e.end_date, e.worker_category_code, e.employee_category_code, pa.org_unit_id,"
                        + " pa.position_id, pa.job_title_id, pa.location_id, p.full_name, p.national_id_last4,"
                        + " count(*) over () as total from " + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id"
                        + " where e.tenant_id = :t and " + scope.sql()
                        + " and (cast(:q as text) is null or p.full_name_search like '%' || sys.search_key(:q) || '%'"
                        + "      or e.employee_no ilike :q || '%')"
                        + " and (cast(:le as uuid) is null or e.legal_entity_id = :le)"
                        + " and (cast(:ou as uuid) is null or pa.org_unit_id = :ou)"
                        + " and (cast(:wc as text) is null or e.worker_category_code = :wc)"
                        + " and (cast(:ec as text) is null or e.employee_category_code = :ec)"
                        + " and (case when cast(:st as text) is not null then e.status = :st"
                        + "      else e.status <> 'CANCELLED' and (e.employment_period @> cast(:asOf as date)"
                        + "           or (e.status = 'PRE_HIRE' and e.hire_date > cast(:asOf as date))) end)"
                        + " order by p.full_name collate \"vi-x-icu\", e.employee_no"
                        + " limit :size offset :off")
                .param("t", support.tenant()).param("asOf", asOf).param("q", search).param("le", q.legalEntityId())
                .param("ou", q.orgUnitId()).param("wc", q.workerCategoryCode()).param("ec", q.employeeCategoryCode())
                .param("st", q.status()).param("size", size).param("off", (long) page * size).params(scope.params())
                .query((rs, n) -> {
                    if (total.isEmpty()) {
                        total.add(rs.getLong("total"));
                    }
                    return row(rs);
                })
                .list();
        return new Page(rows, total.isEmpty() ? 0 : total.getFirst(), page, size);
    }

    @HrTransactional(readOnly = true)
    public Profile profile(UUID employmentId, LocalDate asOfParam) {
        LocalDate asOf = support.orToday(asOfParam);
        access.require(PeoplePermissions.EMPLOYEE_VIEW, employmentId, asOf);
        masking.apply(PeoplePermissions.EMPLOYEE_VIEW);
        UUID t = support.tenant();
        EmploymentSummary employment = db.jdbc().sql("select * from hr.employment where tenant_id = :t and id = :id")
                .param("t", t).param("id", employmentId).query((rs, n) -> employment(rs)).single();
        UUID personId = db.jdbc().sql("select person_id from hr.employment where tenant_id = :t and id = :id")
                .param("t", t).param("id", employmentId).query(UUID.class).single();
        PersonView person = persons.find(personId).orElseThrow();
        List<EmploymentSummary> others = db.jdbc().sql("""
                        select * from hr.employment where tenant_id = :t and person_id = :p and id <> :id
                         order by hire_date""")
                .param("t", t).param("p", personId).param("id", employmentId)
                .query((rs, n) -> employment(rs)).list().stream()
                .filter(e -> access.can(PeoplePermissions.EMPLOYEE_VIEW, e.employmentId(), asOf)).toList();
        AssignmentSummary assignment = db.jdbc().sql("""
                        select a.*, mp.full_name as manager_name from hr.assignment a
                          left join hr.employment me on me.tenant_id = a.tenant_id and me.id = a.manager_employment_id
                          left join hr.person mp on mp.tenant_id = me.tenant_id and mp.id = me.person_id
                         where a.tenant_id = :t and a.employment_id = :e and a.assignment_type = 'PRIMARY'
                           and a.superseded_at is null
                         order by (a.valid_during @> cast(:d as date)) desc, a.valid_from desc limit 1""")
                .param("t", t).param("e", employmentId).param("d", asOf)
                .query((rs, n) -> new AssignmentSummary(rs.getObject("id", UUID.class), rs.getString("assignment_type"),
                        rs.getObject("legal_entity_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                        rs.getObject("position_id", UUID.class), rs.getObject("job_title_id", UUID.class),
                        rs.getObject("grade_id", UUID.class), rs.getObject("location_id", UUID.class),
                        rs.getObject("manager_employment_id", UUID.class), rs.getString("manager_name"),
                        rs.getBigDecimal("fte"), rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class)))
                .optional().orElse(null);
        return new Profile(employment, person, others, assignment, tabs(employmentId, personId, asOf), asOf);
    }

    /** Tabs of the profile the caller may open (hidden without the tab's permission in scope, US-PPL-016 AC). */
    private Map<String, String> tabs(UUID employmentId, UUID personId, LocalDate asOf) {
        String p = "/api/v1/people/persons/" + personId;
        String e = "/api/v1/people/employments/" + employmentId;
        Map<String, String[]> all = new LinkedHashMap<>();
        all.put("person", new String[] {PeoplePermissions.EMPLOYEE_VIEW, p});
        all.put("addresses", new String[] {PeoplePermissions.EMPLOYEE_VIEW, p + "/addresses"});
        all.put("contacts", new String[] {PeoplePermissions.EMPLOYEE_VIEW, p + "/contacts"});
        all.put("identityDocuments", new String[] {PeoplePermissions.EMPLOYEE_VIEW, p + "/identity-documents"});
        all.put("qualifications", new String[] {PeoplePermissions.EMPLOYEE_VIEW, p + "/qualifications"});
        all.put("taxResidency", new String[] {PeoplePermissions.EMPLOYEE_VIEW, p + "/tax-residencies"});
        all.put("workPermits", new String[] {PeoplePermissions.EMPLOYEE_VIEW, p + "/work-permits"});
        all.put("specialStatuses", new String[] {PeoplePermissions.SPECIAL_STATUS_VIEW, p + "/special-statuses"});
        all.put("assignments", new String[] {PeoplePermissions.EMPLOYEE_VIEW, e + "/assignments"});
        all.put("contracts", new String[] {PeoplePermissions.CONTRACT_VIEW, e + "/contracts"});
        all.put("decisions", new String[] {PeoplePermissions.DECISION_VIEW, e + "/decisions"});
        all.put("compensation", new String[] {PeoplePermissions.COMPENSATION_VIEW, e + "/salary-records"});
        all.put("familyMembers", new String[] {PeoplePermissions.DEPENDANT_VIEW, p + "/family-members"});
        all.put("dependants", new String[] {PeoplePermissions.DEPENDANT_VIEW, p + "/dependants"});
        all.put("bankAccounts", new String[] {PeoplePermissions.BANK_ACCOUNT_VIEW, p + "/bank-accounts"});
        all.put("separation", new String[] {PeoplePermissions.SEPARATION_VIEW, e + "/separations"});
        Map<String, String> out = new LinkedHashMap<>();
        all.forEach((tab, x) -> {
            if (access.can(x[0], employmentId, asOf)) {
                out.put(tab, x[1]);
            }
        });
        return out;
    }

    private static EmploymentSummary employment(ResultSet rs) throws SQLException {
        return new EmploymentSummary(rs.getObject("id", UUID.class), rs.getString("employee_no"),
                rs.getObject("legal_entity_id", UUID.class), rs.getString("hire_type"), rs.getString("status"),
                rs.getObject("hire_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getObject("seniority_date", LocalDate.class), rs.getString("worker_category_code"),
                rs.getString("employee_category_code"));
    }

    private static EmployeeRow row(ResultSet rs) throws SQLException {
        String last4 = rs.getString("national_id_last4");
        return new EmployeeRow(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getString("employee_no"), rs.getString("full_name"), last4 == null ? null : "••••" + last4,
                rs.getObject("legal_entity_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                rs.getObject("position_id", UUID.class), rs.getObject("job_title_id", UUID.class),
                rs.getObject("location_id", UUID.class), rs.getString("status"),
                rs.getObject("hire_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getString("worker_category_code"), rs.getString("employee_category_code"));
    }
}
