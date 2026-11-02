package vn.yourhrm.support;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * People test data written as the superuser (bypasses RLS): persons, employments and primary assignments, and HR users
 * holding {@code hr.*} permissions. For setting up state only; the behaviour under test goes through the API.
 */
public final class PeopleFixtures {

    private final JdbcTemplate admin;
    private final TestFixtures fix;

    public PeopleFixtures(JdbcTemplate admin, TestFixtures fix) {
        this.admin = admin;
        this.fix = fix;
    }

    /** An employee: the person, an employment in {@code legalEntity} and its PRIMARY assignment from the hire date. */
    public record Employee(UUID personId, UUID employmentId, UUID assignmentKey) {
    }

    /** All active hr.* permission codes of the global catalogue (registered by People at start-up). */
    public List<String> allHrPermissions() {
        return admin.queryForList("select permission_code from core.permission where owner_module = 'hr' and is_active order by 1",
                String.class);
    }

    /** An HR administrator: every core.* and hr.* permission, TENANT scope. */
    public TestUser hrAdmin(UUID tenant) {
        List<String> codes = new java.util.ArrayList<>(fix.allCorePermissions());
        codes.addAll(allHrPermissions());
        UUID role = fix.role(tenant, "HR_ALL_" + Ids.newId().toString().substring(24), false, codes.toArray(String[]::new));
        TestUser user = fix.user(tenant, "hr-" + Ids.newId().toString().substring(24) + "@test.local");
        fix.assign(user, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        return user;
    }

    /** A user holding {@code permissions} with the given scope (e.g. LEGAL_ENTITY + ids, SELF + empty). */
    public TestUser userWith(UUID tenant, String scopeType, List<UUID> scopeIds, String... permissions) {
        UUID role = fix.role(tenant, "R_" + Ids.newId().toString().substring(24), false, permissions);
        TestUser user = fix.user(tenant, "u-" + Ids.newId().toString().substring(24) + "@test.local");
        fix.assign(user, role, scopeType, scopeIds, true, TestFixtures.EPOCH, null);
        return user;
    }

    /**
     * The tenant's number series People draws from (BR-CORE-085): EMPLOYEE_NO (tenant), CONTRACT_NO and DECISION_NO
     * (per legal entity, yearly). Idempotent.
     */
    public void numberSeries(UUID tenant) {
        admin.update("""
                insert into core.sequence_definition (tenant_id, code, name, scope_level, pattern, reset_rule)
                values (?, 'EMPLOYEE_NO', 'Mã nhân viên', 'TENANT', 'NV{SEQ:5}', 'NEVER'),
                       (?, 'CONTRACT_NO', 'Số hợp đồng', 'LEGAL_ENTITY', '{SEQ:3}/{YYYY}/HĐLĐ-{LE}', 'YEARLY'),
                       (?, 'DECISION_NO', 'Số quyết định', 'LEGAL_ENTITY', '{SEQ:3}/{YYYY}/QĐ-{LE}', 'YEARLY')
                on conflict (tenant_id, code) do nothing""", tenant, tenant, tenant);
    }

    /** Links a user account to an employment (Core's SELF / reports scopes and ESS). */
    public void link(TestUser user, Employee employee) {
        admin.update("update core.user_account set employment_id = ?, person_id = ? where tenant_id = ? and id = ?",
                employee.employmentId(), employee.personId(), user.tenantId(), user.userId());
    }

    public UUID person(UUID tenant, String familyName, String givenName, LocalDate birthDate) {
        UUID id = Ids.newId();
        admin.update("""
                insert into hr.person (tenant_id, id, family_name, given_name, full_name, birth_date, gender_code)
                values (?, ?, ?, ?, ?, ?, 'F')""",
                tenant, id, familyName, givenName, familyName + " " + givenName,
                birthDate == null ? null : Date.valueOf(birthDate));
        return id;
    }

    public UUID employment(UUID tenant, UUID person, UUID legalEntity, String employeeNo, LocalDate hireDate,
            String status) {
        UUID id = Ids.newId();
        admin.update("""
                insert into hr.employment (tenant_id, id, person_id, legal_entity_id, employee_no, hire_date,
                       original_hire_date, seniority_date, status, worker_category_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'EMPLOYEE')""",
                tenant, id, person, legalEntity, employeeNo, Date.valueOf(hireDate), Date.valueOf(hireDate),
                Date.valueOf(hireDate), status);
        return id;
    }

    /** A PRIMARY assignment line from {@code validFrom} (open-ended); returns its assignment key. */
    public UUID primaryAssignment(UUID tenant, UUID employment, UUID legalEntity, OrgUnit orgUnit, UUID location,
            UUID managerEmployment, LocalDate validFrom) {
        UUID key = Ids.newId();
        admin.update("""
                insert into hr.assignment (tenant_id, id, assignment_key, employment_id, assignment_type,
                       legal_entity_id, paying_legal_entity_id, org_unit_id, location_id, manager_employment_id,
                       action_code, valid_from)
                values (?, ?, ?, ?, 'PRIMARY', ?, ?, ?, ?, ?, 'HIRE', ?)""",
                tenant, Ids.newId(), key, employment, legalEntity, legalEntity, orgUnit.id(), location,
                managerEmployment, Date.valueOf(validFrom));
        return key;
    }

    /** An ACTIVE employee of {@code legalEntity} in {@code orgUnit} hired on {@code hireDate}. */
    public Employee employee(UUID tenant, UUID legalEntity, OrgUnit orgUnit, String familyName, String givenName,
            LocalDate hireDate) {
        return employee(tenant, legalEntity, orgUnit, null, familyName, givenName, hireDate);
    }

    /** As {@link #employee(UUID, UUID, OrgUnit, String, String, LocalDate)}, reporting to {@code manager}. */
    public Employee employee(UUID tenant, UUID legalEntity, OrgUnit orgUnit, Employee manager, String familyName,
            String givenName, LocalDate hireDate) {
        UUID person = person(tenant, familyName, givenName, LocalDate.of(1990, 5, 17));
        UUID employment = employment(tenant, person, legalEntity, "E" + Ids.newId().toString().substring(26),
                hireDate, "ACTIVE");
        UUID key = primaryAssignment(tenant, employment, legalEntity, orgUnit, null,
                manager == null ? null : manager.employmentId(), hireDate);
        return new Employee(person, employment, key);
    }
}
