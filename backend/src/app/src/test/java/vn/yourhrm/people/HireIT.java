package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestTenantLimits;

/**
 * Direct hire (US-PPL-017..020): one-transaction hire, approval through {@code hr.HIRE}, preview / final
 * {@code EmployeeHired}, activation by the daily job, rejection and no-show cancellation, the per-LE rule, the person
 * match, the plan limit, scope and tenant isolation.
 */
class HireIT extends EmploymentTestSupport {

    String body(World w, String who, LocalDate hireDate) {
        return hireBody(who, w.le(), w.unit().id(), w.location(), hireDate, 10_000_000);
    }

    @Test
    void aFutureHireIsCreatedPendingThenPreviewedOnApprovalAndActivatedOnItsHireDate() throws Exception {
        World w = employmentWorld("hire-future");
        LocalDate hireDate = TODAY.plusDays(10);
        var r = postJson(w.hr(), "/api/v1/people/hires", body(w, newPerson("001095000101"), hireDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.hireType").value("NEW"))
                .andExpect(jsonPath("$.personCreated").value(true))
                .andExpect(jsonPath("$.employeeNo").value(startsWith("NV")))
                .andExpect(jsonPath("$.missingData", hasItem("SIGNED_CONTRACT")))
                .andExpect(jsonPath("$.missingData", hasItem("BANK_ACCOUNT")));
        UUID id = UUID.fromString(read(r, "$.employmentId"));
        // one transaction: employment, decision (pending), assignment, contract, salary
        assertThat(employmentStatus(id)).isEqualTo("PRE_HIRE");
        assertThat(ADMIN.queryForObject("select status from hr.decision where employment_id = ? and decision_type_code = 'HIRE'",
                String.class, id)).isEqualTo("PENDING_APPROVAL");
        assertThat(count("select count(*) from hr.assignment where employment_id = ?", id)).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select status from hr.contract where employment_id = ?", String.class, id))
                .isEqualTo("DRAFT");
        assertThat(count("select count(*) from hr.salary_record where employment_id = ?", id)).isEqualTo(1);
        mvc.perform(get("/api/v1/people/employments/" + id).with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hireApproval").value("PENDING"))
                .andExpect(jsonPath("$.incomplete").value(true))
                .andExpect(jsonPath("$.status").value("PRE_HIRE"));
        assertThat(events(w.tenant(), "people.EmployeeHired")).isEmpty();

        decide(w.approver(), id, "APPROVE");
        assertThat(callbackStatus(id)).isEqualTo("ACKED");
        List<String> hired = events(w.tenant(), "people.EmployeeHired");
        assertThat(hired).hasSize(1);
        assertThat((Boolean) JsonPath.read(hired.getFirst(), "$.preview")).isTrue();
        assertThat((String) JsonPath.read(hired.getFirst(), "$.hireType")).isEqualTo("NEW");
        assertThat((String) JsonPath.read(hired.getFirst(), "$.decisionNo")).isNotBlank();
        assertThat(hired.getFirst()).doesNotContain("001095000101");
        assertThat(ADMIN.queryForObject("select status from hr.decision where employment_id = ? and decision_type_code = 'HIRE'",
                String.class, id)).isEqualTo("APPROVED");
        assertThat(count("select count(*) from hr.insurance_participation where employment_id = ? and status = 'ACTIVE' "
                + "and is_participating", id)).isEqualTo(4);

        assertThat(daily(w, TODAY).activated()).isZero();
        assertThat(employmentStatus(id)).isEqualTo("PRE_HIRE");
        assertThat(daily(w, hireDate).activated()).isEqualTo(1);
        assertThat(employmentStatus(id)).isEqualTo("ACTIVE");
        assertThat(daily(w, hireDate).activated()).isZero();
        hired = events(w.tenant(), "people.EmployeeHired");
        assertThat(hired).hasSize(2);
        assertThat((Boolean) JsonPath.read(hired.get(1), "$.preview")).isFalse();
    }

    @Test
    void aBackDatedHireActivatesOnApprovalWithoutDoublePublishingTheFirstVersions() throws Exception {
        World w = employmentWorld("hire-past");
        UUID id = hireApproved(w, body(w, newPerson("001095000102"), TODAY.minusDays(2)));
        assertThat(employmentStatus(id)).isEqualTo("ACTIVE");
        List<String> hired = events(w.tenant(), "people.EmployeeHired");
        assertThat(hired).hasSize(1);
        assertThat((Boolean) JsonPath.read(hired.getFirst(), "$.preview")).isFalse();
        assertThat(ADMIN.queryForObject("select count(*) from hr.outbox_event where employment_id = ? "
                + "and event_type = 'people.SalaryChanged'", Integer.class, id)).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select count(*) from hr.outbox_event where employment_id = ? "
                + "and event_type = 'people.AssignmentChanged'", Integer.class, id)).isEqualTo(1);
        mvc.perform(get("/api/v1/people/employments/" + id + "/status").with(as(w.hr()))
                        .param("asOf", TODAY.minusDays(5).toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.statusOn").value("PRE_HIRE"));
    }

    @Test
    void aRejectedHireIsCancelledWithItsChildRows() throws Exception {
        World w = employmentWorld("hire-reject");
        UUID id = hire(w, body(w, newPerson("001095000103"), TODAY.plusDays(5)));
        decide(w.approver(), id, "REJECT");
        assertThat(employmentStatus(id)).isEqualTo("CANCELLED");
        assertThat(ADMIN.queryForObject("select status from hr.contract where employment_id = ?", String.class, id))
                .isEqualTo("CANCELLED");
        assertThat(ADMIN.queryForObject("select status from hr.decision where employment_id = ?", String.class, id))
                .isEqualTo("CANCELLED");
        List<String> cancelled = events(w.tenant(), "people.HireCancelled");
        assertThat(cancelled).hasSize(1);
        assertThat((String) JsonPath.read(cancelled.getFirst(), "$.cancelKind")).isEqualTo("APPROVAL_REJECTED");
        assertThat(events(w.tenant(), "people.EmployeeHired")).isEmpty();
    }

    @Test
    void aPreHireIsCancelledWithAReasonAndFreesTheLegalEntityForTheSamePerson() throws Exception {
        World w = employmentWorld("hire-cancel");
        LocalDate hireDate = TODAY.plusDays(7);
        UUID id = hireApproved(w, body(w, newPerson("001095000104"), hireDate));
        UUID person = ADMIN.queryForObject("select person_id from hr.employment where id = ?", UUID.class, id);
        String path = "/api/v1/people/hires/" + id + "/cancel";
        postJson(w.hr(), path, "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        postJson(w.hr(), path, "{\"reasonCode\":\"NO_SHOW\",\"reason\":\"Không đến nhận việc\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        List<String> cancelled = events(w.tenant(), "people.HireCancelled");
        assertThat(cancelled).hasSize(1);
        assertThat((String) JsonPath.read(cancelled.getFirst(), "$.cancelKind")).isEqualTo("NO_SHOW");
        assertThat((Boolean) JsonPath.read(cancelled.getFirst(), "$.previewPublished")).isTrue();
        assertThat(count("select count(*) from hr.insurance_participation where employment_id = ? and status = 'ACTIVE'",
                id)).isZero();
        postJson(w.hr(), path, "{\"reason\":\"again\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-status-transition"));
        // the daily job never activates it
        assertThat(daily(w, hireDate).activated()).isZero();
        // CANCELLED frees employment_person_le_ex: the same person can be hired into the same LE again
        hire(w, body(w, existingPerson(person), hireDate));
    }

    @Test
    void theSameLegalEntityIsRedirectedAndAnExistingPersonIsNotDuplicated() throws Exception {
        World w = employmentWorld("hire-samele");
        UUID id = hireApproved(w, body(w, newPerson("001095000105"), TODAY.minusDays(1)));
        UUID person = ADMIN.queryForObject("select person_id from hr.employment where id = ?", UUID.class, id);
        postJson(w.hr(), "/api/v1/people/hires", body(w, existingPerson(person), TODAY.plusDays(3)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("same-legal-entity-employment"))
                .andExpect(jsonPath("$.redirect").value("ASSIGNMENT_CHANGE"));
        postJson(w.hr(), "/api/v1/people/hires", body(w, newPerson("001095000105"), TODAY.plusDays(3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("person-exists"))
                .andExpect(jsonPath("$.personId").value(person.toString()));
        postJson(w.hr(), "/api/v1/people/hires/match", """
                {"nationalIdType":"CCCD","nationalId":"001095000105","legalEntityId":"%s"}""".formatted(w.le()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking[0].candidate.personId").value(person.toString()))
                .andExpect(jsonPath("$.blocking[0].options[0]").value("ASSIGNMENT_CHANGE"));
        postJson(w.hr(), "/api/v1/people/hires", body(w, existingPerson(person), TODAY.plusDays(3))
                        .replace("\"workerCategoryCode\"", "\"hireType\":\"NEW\",\"workerCategoryCode\""))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void thePlanLimitOfActiveEmployeesBlocksANewHire() throws Exception {
        World w = employmentWorld("hire-limit");
        TestTenantLimits.set(w.tenant(), "limit.active_employees", 2); // manager + employee already active
        postJson(w.hr(), "/api/v1/people/hires", body(w, newPerson("001095000106"), TODAY.plusDays(3)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("licence-limit-reached"));
        TestTenantLimits.set(w.tenant(), "limit.active_employees", 3);
        hire(w, body(w, newPerson("001095000106"), TODAY.plusDays(3)));
    }

    @Test
    void hiringNeedsThePermissionInScopeAndEmploymentsStayInTheirTenant() throws Exception {
        World w = employmentWorld("hire-scope");
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        postJson(viewer, "/api/v1/people/hires", body(w, newPerson("001095000107"), TODAY.plusDays(3)))
                .andExpect(status().isForbidden());
        UUID otherLe = FIX.legalEntity(w.tenant(), "LEX");
        TestUser otherLeHr = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(otherLe),
                PeoplePermissions.EMPLOYMENT_CREATE, PeoplePermissions.EMPLOYEE_VIEW);
        postJson(otherLeHr, "/api/v1/people/hires", body(w, newPerson("001095000107"), TODAY.plusDays(3)))
                .andExpect(status().isForbidden());
        UUID id = hire(w, body(w, newPerson("001095000107"), TODAY.plusDays(3)));
        mvc.perform(get("/api/v1/people/employments/" + id).with(as(otherLeHr))).andExpect(status().isNotFound());
        World other = employmentWorld("hire-scope-x");
        mvc.perform(get("/api/v1/people/employments/" + id).with(as(other.hr()))).andExpect(status().isNotFound());
        postJson(other.hr(), "/api/v1/people/hires/" + id + "/cancel", "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound());
    }
}
