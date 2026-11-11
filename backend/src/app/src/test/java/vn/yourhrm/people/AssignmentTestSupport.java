package vn.yourhrm.people;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/** Shared set-up of the assignment and salary integration tests (one tenant per test). */
abstract class AssignmentTestSupport extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final LocalDate TODAY = LocalDate.now(BusinessClock.VIETNAM);
    static final LocalDate HIRE = TODAY.minusYears(1).withDayOfMonth(1);
    static final String ADMIN_UNIT = "ASG-URBAN";

    /**
     * {@code manager} heads {@code unit}; {@code emp} reports to the manager; {@code approver} holds role HR_ADMIN (the
     * vendor approval step); {@code location} is in region I.
     */
    record World(UUID tenant, UUID le, UUID location, OrgUnit unit, OrgUnit unit2, TestUser hr, TestUser approver,
            Employee manager, Employee emp) {
    }

    @BeforeAll
    static void adminUnits() {
        ADMIN.update("""
                insert into core.admin_unit (admin_unit_code, level, name_vi, valid_from)
                values (?, 'COMMUNE', 'Phường thử', date '2020-01-01') on conflict do nothing""", ADMIN_UNIT);
    }

    static World world(String slug) {
        UUID tenant = FIX.tenant(slug);
        UUID le = FIX.legalEntity(tenant, "LE");
        ADMIN.update("update core.legal_entity_version set admin_unit_code = ? where legal_entity_id = ?", ADMIN_UNIT, le);
        UUID location = FIX.location(tenant, le, "HN");
        ADMIN.update("update core.location_version set admin_unit_code = ? where location_id = ?", ADMIN_UNIT, location);
        TestLegalParameters.publish(tenant, 1, Map.of(ADMIN_UNIT, "I"));
        OrgUnit unit = FIX.orgUnit(tenant, "U1", null, le, null, TestFixtures.EPOCH, null);
        OrgUnit unit2 = FIX.orgUnit(tenant, "U2", null, le, null, TestFixtures.EPOCH, null);
        PPL.numberSeries(tenant);
        TestLegalParameters.define(tenant, "assignment.temp_transfer.max_working_days_per_year", 60);
        TestLegalParameters.define(tenant, "assignment.temp_transfer.notice_working_days", 3);
        TestLegalParameters.define(tenant, "assignment.temp_transfer.wage_floor_pct", 85);
        TestLegalParameters.define(tenant, "assignment.temp_transfer.previous_wage_working_days", 30);
        TestLegalParameters.define(tenant, "salary.min_wage.monthly_by_region",
                Map.of("I", 4_960_000, "II", 4_410_000, "III", 3_860_000, "IV", 3_450_000));
        TestLegalParameters.define(tenant, "salary.min_wage.hourly_by_region",
                Map.of("I", 23_800, "II", 21_200, "III", 18_600, "IV", 16_600));
        TestLegalParameters.define(tenant, "salary.probation.min_pct", 85);
        TestUser hr = PPL.hrAdmin(tenant);
        List<String> codes = new ArrayList<>(FIX.allCorePermissions());
        codes.addAll(PPL.allHrPermissions());
        UUID role = FIX.role(tenant, "HR_ADMIN", false, codes.toArray(String[]::new));
        TestUser approver = FIX.user(tenant, "approver-" + tenant.toString().substring(30) + "@test.local");
        FIX.assign(approver, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        Employee manager = PPL.employee(tenant, le, unit, "Trần", "Quản", HIRE);
        Employee emp = PPL.employee(tenant, le, unit, manager, "Nguyễn", "An", HIRE);
        return new World(tenant, le, location, unit, unit2, hr, approver, manager, emp);
    }

    static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), call);
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Approves (or rejects) the pending task of the workflow whose subject is {@code subjectId}. */
    void decide(TestUser approver, UUID subjectId, String action) throws Exception {
        UUID task = ADMIN.queryForObject("""
                select t.id from core.workflow_task t
                  join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                 where i.subject_id = ? and t.status = 'PENDING'""", UUID.class, subjectId);
        postJson(approver, "/api/v1/core/workflow/tasks/" + task + "/actions",
                "{\"action\":\"" + action + "\",\"comment\":\"checked\"}").andExpect(status().isNoContent());
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    static <T> T read(ResultActions r, String path) throws Exception {
        return JsonPath.read(body(r), path);
    }

    static List<String> events(UUID tenant, String eventType) {
        return ADMIN.queryForList("select payload::text from hr.outbox_event where tenant_id = ? and event_type = ? order by id",
                String.class, tenant, eventType);
    }

    static String callbackStatus(UUID subjectId) {
        return ADMIN.queryForObject("""
                select c.status || coalesce(': ' || c.last_error, '') from core.workflow_callback c
                  join core.workflow_instance i on i.tenant_id = c.tenant_id and i.id = c.workflow_instance_id
                 where i.subject_id = ?""", String.class, subjectId);
    }
}
