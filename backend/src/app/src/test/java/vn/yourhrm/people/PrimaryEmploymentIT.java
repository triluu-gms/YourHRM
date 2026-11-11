package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * PIT primary employment (US-PPL-022/024): approved designation changes from a month start, the validation rules,
 * OUT-36, and the forced take-over when the primary employment ends.
 */
class PrimaryEmploymentIT extends EmploymentTestSupport {

    record Concurrent(World w, UUID emp1, UUID emp2, LocalDate hired) {
    }

    Concurrent concurrent(String slug) throws Exception {
        World w = employmentWorld(slug);
        SecondLe le2 = secondLe(w);
        LocalDate d = TODAY.minusMonths(2).withDayOfMonth(15);
        UUID emp2 = hireApproved(w, hireBody(existingPerson(w.emp().personId()), le2.le(), le2.unit().id(),
                le2.location(), d, 12_000_000));
        return new Concurrent(w, w.emp().employmentId(), emp2, d);
    }

    String path(Concurrent c) {
        return "/api/v1/people/persons/" + c.w().emp().personId() + "/primary-employment";
    }

    @Test
    void anApprovedChangeMovesThePrimaryFromAMonthStart() throws Exception {
        Concurrent c = concurrent("prim-change");
        LocalDate from = TODAY.withDayOfMonth(1);
        String body = "{\"employmentId\":\"%s\",\"validFrom\":\"%s\",\"reasonCode\":\"EMPLOYEE_REGISTRATION\"}";
        postJson(c.w().hr(), path(c) + "/change-requests", body.formatted(c.emp2(), from.plusDays(1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("primary-change-month-start"));
        postJson(c.w().hr(), path(c) + "/change-requests", body.formatted(c.emp1(), from))
                .andExpect(jsonPath("$.code").value("already-primary"));
        var r = postJson(c.w().hr(), path(c) + "/change-requests", body.formatted(c.emp2(), from))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        UUID request = UUID.fromString(read(r, "$.requestId"));
        mvc.perform(get(path(c)).with(as(c.w().hr())))
                .andExpect(jsonPath("$.pending[0].employmentId").value(c.emp2().toString()));
        decide(c.w().approver(), request, "APPROVE");
        assertThat(callbackStatus(request)).isEqualTo("ACKED");

        mvc.perform(get(path(c)).with(as(c.w().hr())).param("asOf", from.toString()))
                .andExpect(jsonPath("$.primaryEmploymentId").value(c.emp2().toString()))
                .andExpect(jsonPath("$.pending.length()").value(0));
        mvc.perform(get(path(c)).with(as(c.w().hr())).param("asOf", from.minusDays(1).toString()))
                .andExpect(jsonPath("$.primaryEmploymentId").value(c.emp1().toString()));
        List<String> changed = events(c.w().tenant(), "people.PrimaryEmploymentChanged");
        assertThat(changed).hasSize(2);
        String last = changed.get(1);
        assertThat((String) JsonPath.read(last, "$.reasonCode")).isEqualTo("EMPLOYEE_REGISTRATION");
        assertThat((String) JsonPath.read(last, "$.previousEmploymentId")).isEqualTo(c.emp1().toString());
        assertThat((String) JsonPath.read(last, "$.validFrom")).isEqualTo(from.toString());
        // exactly one ACTIVE designation per day
        assertThat(count("""
                select count(*) from hr.primary_employment where person_id = ? and status = 'ACTIVE'
                   and valid_during @> ?::date""", c.w().emp().personId(), Date.valueOf(from))).isEqualTo(1);
    }

    @Test
    void aSingleEmploymentNeedsNoDesignationAndChangesNeedThePermissionInScope() throws Exception {
        World w = employmentWorld("prim-single");
        String p = "/api/v1/people/persons/" + w.emp().personId() + "/primary-employment";
        mvc.perform(get(p).with(as(w.hr())))
                .andExpect(jsonPath("$.basis").value("SINGLE_EMPLOYMENT"))
                .andExpect(jsonPath("$.primaryEmploymentId").value(w.emp().employmentId().toString()));
        postJson(w.hr(), p + "/change-requests", "{\"employmentId\":\"%s\",\"validFrom\":\"%s\"}"
                .formatted(w.emp().employmentId(), TODAY.withDayOfMonth(1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("primary-designation-not-needed"));
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        postJson(viewer, p + "/change-requests", "{\"employmentId\":\"%s\",\"validFrom\":\"%s\"}"
                .formatted(w.emp().employmentId(), TODAY.withDayOfMonth(1)))
                .andExpect(status().isForbidden());
        World other = employmentWorld("prim-single-x");
        mvc.perform(get(p).with(as(other.hr()))).andExpect(status().isNotFound());
    }

    @Test
    void whenThePrimaryEndsTheRemainingEmploymentTakesOverAndInsuranceIsRederived() throws Exception {
        Concurrent c = concurrent("prim-ended");
        LocalDate end = TODAY.plusDays(20);
        // the separation stream sets the end date (simulated)
        ADMIN.update("update hr.employment set end_date = ? where id = ?", Date.valueOf(end), c.emp1());
        var summary = daily(c.w(), TODAY);
        assertThat(summary.endedPrimaries()).isEqualTo(1);
        assertThat(summary.insuranceRederived()).isEqualTo(1);
        String last = events(c.w().tenant(), "people.PrimaryEmploymentChanged").getLast();
        assertThat((String) JsonPath.read(last, "$.reasonCode")).isEqualTo("PRIMARY_ENDED");
        assertThat((String) JsonPath.read(last, "$.employmentId")).isEqualTo(c.emp2().toString());
        mvc.perform(get(path(c)).with(as(c.w().hr())).param("asOf", end.plusDays(1).toString()))
                .andExpect(jsonPath("$.basis").value("SINGLE_EMPLOYMENT"))
                .andExpect(jsonPath("$.primaryEmploymentId").value(c.emp2().toString()));
        mvc.perform(get("/api/v1/people/employments/" + c.emp2() + "/insurance-participation").with(as(c.w().hr()))
                        .param("asOf", end.plusDays(1).toString()))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].participating").value(true));
        mvc.perform(get("/api/v1/people/employments/" + c.emp1() + "/insurance-participation").with(as(c.w().hr()))
                        .param("asOf", end.plusDays(1).toString()))
                .andExpect(jsonPath("$.length()").value(0));
        // idempotent
        assertThat(daily(c.w(), TODAY).endedPrimaries()).isZero();
    }
}
