package vn.yourhrm.people;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/** Shared set-up of the contract and decision integration tests (one tenant per test). */
abstract class ContractTestBase extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final LocalDate HIRE = LocalDate.of(2025, 1, 1);
    static final byte[] PDF = "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    record World(UUID tenant, UUID le, OrgUnit unit, TestUser hr, Employee emp) {
    }

    /** A tenant with a legal entity, number series, the contract legal parameters, an HR admin and one employee. */
    static World world(String slug) {
        UUID tenant = FIX.tenant(slug);
        UUID le = FIX.legalEntity(tenant, "LE");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        PPL.numberSeries(tenant);
        legalParameters(tenant);
        TestUser hr = PPL.hrAdmin(tenant);
        Employee emp = PPL.employee(tenant, le, unit, "Nguyễn", "An", HIRE);
        return new World(tenant, le, unit, hr, emp);
    }

    static void legalParameters(UUID tenant) {
        TestLegalParameters.define(tenant, "contract.definite.max_months", 36);
        TestLegalParameters.define(tenant, "contract.definite.max_consecutive", 2);
        TestLegalParameters.define(tenant, "contract.definite.consecutive_exceptions",
                List.of("ELDERLY_WORKER", "FOREIGN_WORKER", "SOE_MANAGER", "UNION_OFFICER_TERM"));
        TestLegalParameters.define(tenant, "contract.definite.auto_convert_after_days", 30);
        TestLegalParameters.define(tenant, "contract.short_term.threshold_months", 1);
        TestLegalParameters.define(tenant, "contract.probation.max_days",
                Map.of("ENTERPRISE_MANAGER", 180, "COLLEGE_OR_ABOVE", 60, "INTERMEDIATE", 30, "OTHER", 6));
        TestLegalParameters.define(tenant, "contract.probation.min_pay_pct", 85);
        TestLegalParameters.define(tenant, "contract.suspension.return_window_days", 15);
    }

    static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), call);
    }

    ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mvc.perform(post(path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions createContract(TestUser user, UUID employment, String json) throws Exception {
        return postJson(user, "/api/v1/people/employments/" + employment + "/contracts", json);
    }

    static String definite(String start, String end) {
        return "{\"contractTypeCode\":\"DEFINITE\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}";
    }

    ResultActions sign(TestUser user, String path, String signDate, int version) throws Exception {
        var req = multipart(path).file(new MockMultipartFile("file", "scan.pdf", "application/pdf", PDF))
                .param("version", String.valueOf(version));
        if (signDate != null) {
            req.param("signDate", signDate);
        }
        return mvc.perform(req.with(as(user)));
    }

    /** Creates and signs a contract; returns its id. */
    UUID signedContract(World w, UUID employment, String json) throws Exception {
        UUID id = id(createContract(w.hr(), employment, json).andReturn().getResponse().getContentAsString());
        sign(w.hr(), "/api/v1/people/contracts/" + id + "/sign", "2025-01-01", 0);
        return id;
    }

    static UUID id(String body) {
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Payloads of {@code eventType} published for the tenant, oldest first. */
    static List<String> events(UUID tenant, String eventType) {
        return ADMIN.queryForList("select payload::text from hr.outbox_event where tenant_id = ? and event_type = ? order by id",
                String.class, tenant, eventType);
    }

    static int notifications(UUID tenant, String eventCode) {
        return ADMIN.queryForObject("select count(*) from core.notification where tenant_id = ? and event_code = ?",
                Integer.class, tenant, eventCode);
    }
}
