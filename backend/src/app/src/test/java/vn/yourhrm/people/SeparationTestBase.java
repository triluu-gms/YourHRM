package vn.yourhrm.people;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultHandler;
import vn.yourhrm.people.internal.application.separation.SeparationKeys;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Shared set-up of the separation tests: the separation legal parameters (all [verify-legal] example values of the
 * BRD), a tenant with an HR user, a second HR_ADMIN approver and an employee with an indefinite contract.
 */
abstract class SeparationTestBase extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final LocalDate HIRED = LocalDate.of(2024, 1, 1);

    record World(UUID tenant, UUID le, OrgUnit unit, Employee emp, TestUser hr, TestUser approver) {
    }

    static Map<String, Object> ground(String code, String side, String severance) {
        return Map.of("statutory_code", code, "notice_side", side, "severance", severance);
    }

    static Map<String, Object> rule(int days, String basis) {
        return Map.of("days", days, "basis", basis);
    }

    @BeforeAll
    static void legalParameters() {
        TestLegalParameters.define(SeparationKeys.LEGAL_GROUNDS, Map.ofEntries(
                Map.entry("CONTRACT_EXPIRY", ground("TV-HHD", "NONE", "SEVERANCE_ALLOWANCE")),
                Map.entry("MUTUAL_AGREEMENT", ground("TV-TT", "NONE", "SEVERANCE_ALLOWANCE")),
                Map.entry("EMPLOYEE_RESIGNATION", ground("TV-NLD", "EMPLOYEE", "SEVERANCE_ALLOWANCE")),
                Map.entry("EMPLOYER_UNILATERAL", ground("TV-NSDLD", "EMPLOYER", "SEVERANCE_ALLOWANCE")),
                Map.entry("REDUNDANCY", ground("TV-MVL", "EMPLOYER", "JOB_LOSS_ALLOWANCE")),
                Map.entry("DISMISSAL", ground("TV-ST", "NONE", "NONE")),
                Map.entry("PROBATION_FAILED", ground("TV-TV", "NONE", "NONE")),
                Map.entry("RETIREMENT", ground("TV-NH", "NONE", "NONE")),
                Map.entry("DEATH", ground("TV-CHET", "NONE", "SEVERANCE_ALLOWANCE")),
                Map.entry("MISSING_DECLARED", ground("TV-MT", "NONE", "SEVERANCE_ALLOWANCE")),
                Map.entry("ENTITY_CLOSURE", ground("TV-CDHD", "NONE", "JOB_LOSS_ALLOWANCE"))));
        Map<String, Object> side = Map.of("INDEFINITE", rule(45, "CALENDAR"), "DEFINITE_LONG", rule(30, "CALENDAR"),
                "DEFINITE_SHORT", rule(3, "WORKING"), "PROBATION", rule(0, "CALENDAR"));
        TestLegalParameters.define(SeparationKeys.NOTICE_DAYS, Map.of("EMPLOYEE", side, "EMPLOYER", side));
        TestLegalParameters.define(SeparationKeys.NOTICE_LONG_MIN_MONTHS, 12);
        TestLegalParameters.define(SeparationKeys.NOTICE_SPECIAL_TRADES, Map.of("AVIATION_CREW", Map.of(
                "INDEFINITE", Map.of("days", 120), "DEFINITE_LONG", Map.of("days", 120),
                "DEFINITE_SHORT", Map.of("term_fraction", "0.25"))));
        TestLegalParameters.define(SeparationKeys.NOTICE_EXEMPT_REASONS, Map.of(
                "EMPLOYEE", List.of("NOT_PAID", "MALTREATED"), "EMPLOYER", List.of("ABSENT_5_DAYS")));
        TestLegalParameters.define(SeparationKeys.NOTICE_SHORTFALL_CONSEQUENCES, Map.of(
                "EMPLOYEE", List.of("NO_SEVERANCE", "PAY_HALF_MONTH_SALARY", "PAY_WAGES_FOR_SHORTFALL_DAYS"),
                "EMPLOYER", List.of("PAY_WAGES_FOR_SHORTFALL_DAYS")));
    }

    World world(String name) {
        UUID tenant = FIX.tenant(name);
        UUID le = FIX.legalEntity(tenant, "LE");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Nguyễn", "An", HIRED);
        contract(tenant, emp.employmentId(), "INDEFINITE", HIRED, null);
        PPL.numberSeries(tenant);
        List<String> codes = new ArrayList<>(FIX.allCorePermissions());
        codes.addAll(PPL.allHrPermissions());
        UUID role = FIX.role(tenant, "HR_ADMIN", false, codes.toArray(String[]::new));
        TestUser approver = FIX.user(tenant, "approver-" + Ids.newId().toString().substring(28) + "@test.local");
        FIX.assign(approver, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        return new World(tenant, le, unit, emp, PPL.hrAdmin(tenant), approver);
    }

    static UUID contract(UUID tenant, UUID employment, String type, LocalDate start, LocalDate end) {
        UUID id = Ids.newId();
        ADMIN.update("""
                insert into hr.contract (tenant_id, id, employment_id, contract_no, contract_type_code, start_date,
                       end_date, sign_date, status, definite_seq)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)""",
                tenant, id, employment, "HD-" + id.toString().substring(24), type, Date.valueOf(start),
                end == null ? null : Date.valueOf(end), Date.valueOf(start),
                "DEFINITE".equals(type) ? 1 : null);
        return id;
    }

    static String attrs(String ground, String noticeDate, String lwd, String extra) {
        return "{\"legalGroundCode\":\"" + ground + "\",\"noticeDate\":\"" + noticeDate + "\",\"lastWorkingDate\":\""
                + lwd + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    /** Server errors fail with the exception behind them (instead of a bare 500). */
    static final ResultHandler FAIL_ON_500 = r -> {
        if (r.getResponse().getStatus() >= 500 && r.getResolvedException() != null) {
            throw new AssertionError("HTTP " + r.getResponse().getStatus() + ": " + r.getResolvedException(),
                    r.getResolvedException());
        }
    };

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andDo(FAIL_ON_500);
    }

    ResultActions create(TestUser user, UUID employment, String json) throws Exception {
        return postJson(user, "/api/v1/people/employments/" + employment + "/separation", json);
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    static UUID separationId(ResultActions r) throws Exception {
        return UUID.fromString(JsonPath.read(body(r), "$.separation.id"));
    }

    static int version(UUID separation) {
        return ADMIN.queryForObject("select version from hr.separation where id = ?", Integer.class, separation);
    }

    ResultActions submit(TestUser user, UUID separation, String extra) throws Exception {
        return postJson(user, "/api/v1/people/separations/" + separation + "/submit",
                "{\"version\":" + version(separation) + (extra == null ? "" : "," + extra) + "}");
    }

    void decide(TestUser approver, UUID subjectId, String action) throws Exception {
        UUID task = ADMIN.queryForObject("""
                select t.id from core.workflow_task t
                  join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                 where i.subject_id = ? and t.status = 'PENDING' order by t.created_at desc limit 1""", UUID.class,
                subjectId);
        postJson(approver, "/api/v1/core/workflow/tasks/" + task + "/actions",
                "{\"action\":\"" + action + "\",\"comment\":\"ok\"}").andExpect(status().isNoContent());
    }

    /** A scheduled separation: created, submitted (no e-mail) and approved. */
    UUID scheduled(World w, String json) throws Exception {
        UUID id = separationId(create(w.hr(), w.emp().employmentId(), json).andExpect(status().isCreated()));
        submit(w.hr(), id, "\"noPersonalEmail\":true").andExpect(status().isOk());
        decide(w.approver(), id, "APPROVE");
        return id;
    }

    static Map<String, Object> row(UUID separation) {
        return ADMIN.queryForMap("select * from hr.separation where id = ?", separation);
    }

    static List<Map<String, Object>> events(UUID tenant, String type) {
        return ADMIN.queryForList("""
                select replace(payload::text, '": ', '":') as payload, is_retro, effective_date from hr.outbox_event
                 where tenant_id = ? and event_type = ? order by created_at, id""", tenant, type);
    }

    static <T> T in(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), call);
    }
}
