package vn.yourhrm.people;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.people.api.EmploymentJobs;
import vn.yourhrm.people.api.EmploymentJobs.DailySummary;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestLegalParameters;

/** Shared set-up of the employment-stream tests: the assignment world plus contract and insurance parameters. */
abstract class EmploymentTestSupport extends AssignmentTestSupport {

    static final String RULE = "insurance.multi_contract_rule";

    @Autowired
    EmploymentJobs jobs;

    /** A second legal entity of the tenant with a location (region I) and an org unit. */
    record SecondLe(UUID le, UUID location, OrgUnit unit) {
    }

    static World employmentWorld(String slug) {
        World w = world(slug);
        ContractTestBase.legalParameters(w.tenant());
        TestLegalParameters.define(w.tenant(), RULE,
                Map.of("SI", "FIRST_CONTRACT", "HI", "FIRST_CONTRACT", "UI", "FIRST_CONTRACT", "OA_OD", "EACH_EMPLOYER"));
        return w;
    }

    static SecondLe secondLe(World w) {
        UUID le = FIX.legalEntity(w.tenant(), "LE2");
        ADMIN.update("update core.legal_entity_version set admin_unit_code = ? where legal_entity_id = ?", ADMIN_UNIT, le);
        UUID location = FIX.location(w.tenant(), le, "HCM");
        ADMIN.update("update core.location_version set admin_unit_code = ? where location_id = ?", ADMIN_UNIT, location);
        OrgUnit unit = FIX.orgUnit(w.tenant(), "L2U", null, le, null, TestFixtures.EPOCH, null);
        return new SecondLe(le, location, unit);
    }

    static String newPerson(String nationalId) {
        return """
                "person":{"familyName":"Lê","givenName":"Bình","birthDate":"1995-03-02","nationalIdType":"CCCD",
                          "nationalId":"%s"}""".formatted(nationalId);
    }

    static String hireBody(String who, UUID le, UUID unit, UUID location, LocalDate hireDate, long salary) {
        return """
                {%s,"legalEntityId":"%s","hireDate":"%s","workerCategoryCode":"EMPLOYEE",
                 "assignment":{"orgUnitId":"%s","locationId":"%s"},
                 "contract":{"contractTypeCode":"INDEFINITE"},
                 "salary":{"payBasis":"GROSS","baseSalaryAmount":%d,"siSalaryAmount":%d},
                 "signatoryName":"Giám đốc","signatoryTitle":"Giám đốc"}"""
                .formatted(who, le, hireDate, unit, location, salary, salary);
    }

    static String existingPerson(UUID personId) {
        return "\"personId\":\"" + personId + "\"";
    }

    UUID hire(World w, String body) throws Exception {
        var r = postJson(w.hr(), "/api/v1/people/hires", body);
        r.andExpect(status().isOk());
        return UUID.fromString(read(r, "$.employmentId"));
    }

    /** Submits and approves a hire; returns the employment id. */
    UUID hireApproved(World w, String body) throws Exception {
        UUID id = hire(w, body);
        decide(w.approver(), id, "APPROVE");
        return id;
    }

    DailySummary daily(World w, LocalDate day) throws Exception {
        return as(w.hr(), () -> jobs.runDaily(day));
    }

    static String employmentStatus(UUID employmentId) {
        return ADMIN.queryForObject("select status from hr.employment where id = ?", String.class, employmentId);
    }

    static int count(String sql, Object... args) {
        return ADMIN.queryForObject(sql, Integer.class, args);
    }
}
