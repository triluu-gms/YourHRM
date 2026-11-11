package vn.yourhrm.people;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/** Employee list and profile (US-PPL-015/016): accent-insensitive search, filters, as-of, scope, tabs, masking. */
class PersonEmployeeDirectoryIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final String EMPLOYEES = "/api/v1/people/employees";

    @Test
    void theListSearchesWithoutAccentsFiltersByScopeAndDate() throws Exception {
        UUID tenant = FIX.tenant("ppl-dir");
        UUID le = FIX.legalEntity(tenant, "LE");
        UUID le2 = FIX.legalEntity(tenant, "LE2");
        OrgUnit sales = FIX.orgUnit(tenant, "SALES", null, le, null, TestFixtures.EPOCH, null);
        OrgUnit ops = FIX.orgUnit(tenant, "OPS", null, le2, null, TestFixtures.EPOCH, null);
        Employee an = PPL.employee(tenant, le, sales, "Nguyễn Văn", "An", LocalDate.of(2025, 1, 6));
        PPL.employee(tenant, le, sales, "Đặng", "Ánh", LocalDate.of(2025, 1, 6));
        PPL.employee(tenant, le2, ops, "Nguyễn", "Bình", LocalDate.of(2025, 1, 6));
        Employee future = PPL.employee(tenant, le, sales, "Trần", "Cúc", LocalDate.of(2030, 1, 1));
        ADMIN.update("update hr.employment set status = 'PRE_HIRE' where id = ?", future.employmentId());
        ADMIN.update("update hr.person set national_id_last4 = '2345' where id = ?", an.personId());
        TestUser hr = PPL.hrAdmin(tenant);

        mvc.perform(get(EMPLOYEES + "?q=nguyen van an").with(as(hr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].fullName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.items[0].nationalIdMasked").value("••••2345"));
        mvc.perform(get(EMPLOYEES + "?q=nguyen").with(as(hr))).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(EMPLOYEES + "?q=dang anh").with(as(hr))).andExpect(jsonPath("$.total").value(1));
        // Vietnamese order: Đặng before Nguyễn ... before Trần (pre-hire starting later is listed)
        mvc.perform(get(EMPLOYEES + "?legalEntityId=" + le).with(as(hr))).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].fullName").value("Đặng Ánh"))
                .andExpect(jsonPath("$.items[2].fullName").value("Trần Cúc"));
        mvc.perform(get(EMPLOYEES + "?orgUnitId=" + ops.id()).with(as(hr))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(EMPLOYEES + "?status=PRE_HIRE").with(as(hr))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(EMPLOYEES + "?asOf=2024-12-31&legalEntityId=" + le).with(as(hr)))
                .andExpect(jsonPath("$.total").value(1));       // only the future pre-hire
        mvc.perform(get(EMPLOYEES + "?size=1&page=1&legalEntityId=" + le).with(as(hr)))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.total").value(3));

        TestUser salesHr = PPL.userWith(tenant, "ORG_UNIT", List.of(sales.id()), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(EMPLOYEES).with(as(salesHr))).andExpect(jsonPath("$.total").value(3));
        TestUser other = PPL.hrAdmin(FIX.tenant("ppl-dir-other"));
        mvc.perform(get(EMPLOYEES + "?q=nguyen").with(as(other))).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void theProfileShowsThePersonAssignmentAndPermittedTabsOnly() throws Exception {
        UUID tenant = FIX.tenant("ppl-profile");
        UUID le = FIX.legalEntity(tenant, "LE");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        Employee boss = PPL.employee(tenant, le, unit, "Phạm", "Sếp", LocalDate.of(2020, 1, 6));
        Employee emp = PPL.employee(tenant, le, unit, boss, "Hoàng", "Lan", LocalDate.of(2025, 1, 6));
        TestUser viewer = PPL.userWith(tenant, "LEGAL_ENTITY", List.of(le), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(EMPLOYEES + "/" + emp.employmentId()).with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.person.fullName").value("Hoàng Lan"))
                .andExpect(jsonPath("$.primaryAssignment.orgUnitId").value(unit.id().toString()))
                .andExpect(jsonPath("$.primaryAssignment.managerName").value("Phạm Sếp"))
                .andExpect(jsonPath("$.tabs.addresses").exists())
                .andExpect(jsonPath("$.tabs.compensation").doesNotExist())
                .andExpect(jsonPath("$.tabs.bankAccounts").doesNotExist());
        TestUser hr = PPL.hrAdmin(tenant);
        mvc.perform(get(EMPLOYEES + "/" + emp.employmentId()).with(as(hr)))
                .andExpect(jsonPath("$.tabs.compensation").exists())
                .andExpect(jsonPath("$.person.birthDate").value("1990-05-17"));   // HR admin: mask level NONE
        TestUser stranger = PPL.userWith(tenant, "LEGAL_ENTITY", List.of(FIX.legalEntity(tenant, "LE9")),
                PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(EMPLOYEES + "/" + emp.employmentId()).with(as(stranger))).andExpect(status().isNotFound());
    }
}
