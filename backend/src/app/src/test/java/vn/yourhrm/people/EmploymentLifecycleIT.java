package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Employment records (US-PPL-017): profile reads, derived status and notice state, attribute updates, and the daily
 * headcount snapshot per legal entity (US-PPL-026, OUT-32).
 */
class EmploymentLifecycleIT extends EmploymentTestSupport {

    @Test
    void theProfileShowsEmploymentsWithDerivedStatusAndNotice() throws Exception {
        World w = employmentWorld("emp-read");
        mvc.perform(get("/api/v1/people/persons/" + w.emp().personId() + "/employments").with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].pitPrimary").value(true))
                .andExpect(jsonPath("$[0].hireApproval").value("NONE"));
        String path = "/api/v1/people/employments/" + w.emp().employmentId();
        mvc.perform(get(path + "/status").with(as(w.hr())).param("asOf", HIRE.minusDays(1).toString()))
                .andExpect(jsonPath("$.statusOn").value("PRE_HIRE"));
        // NOTICE is derived from an approved separation (the DDL has no NOTICE status)
        ADMIN.update("""
                insert into hr.separation (tenant_id, employment_id, separation_type, initiated_by, status)
                values (?, ?, 'RESIGNATION', 'EMPLOYEE', 'APPROVED')""", w.tenant(), w.emp().employmentId());
        mvc.perform(get(path).with(as(w.hr())))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.noticeState").value("NOTICE"));
        ADMIN.update("update hr.employment set status = 'SUSPENDED' where id = ?", w.emp().employmentId());
        mvc.perform(get(path + "/status").with(as(w.hr())))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.live").value(true));
        TestUser selfless = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(FIX.legalEntity(w.tenant(), "LEZ")),
                PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(path).with(as(selfless))).andExpect(status().isNotFound());
    }

    @Test
    void hrUpdatesTheEmploymentAttributesWithOptimisticVersion() throws Exception {
        World w = employmentWorld("emp-update");
        String path = "/api/v1/people/employments/" + w.emp().employmentId();
        String body = """
                {"version":%d,"workerCategoryCode":"COLLABORATOR","employeeCategoryCode":"PART_TIME",
                 "seniorityDate":"%s"}""";
        mvc.perform(put(path).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(0, HIRE.minusYears(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerCategoryCode").value("COLLABORATOR"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(path).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(0, HIRE)))
                .andExpect(status().isConflict());
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(put(path).with(as(viewer)).contentType(MediaType.APPLICATION_JSON).content(body.formatted(1, HIRE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theDailyHeadcountCountsActiveEmploymentsPerLegalEntityExcludingSuspended() throws Exception {
        World w = employmentWorld("emp-headcount");
        LocalDate monthEnd = YearMonth.from(TODAY).atEndOfMonth();
        assertThat(as(w.hr(), () -> jobs.takeHeadcountSnapshot(monthEnd))).isEqualTo(1);
        assertThat(as(w.hr(), () -> jobs.takeHeadcountSnapshot(monthEnd))).isZero();
        String e = events(w.tenant(), "people.HeadcountSnapshotTaken").getFirst();
        assertThat((Integer) JsonPath.read(e, "$.activeCount")).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(e, "$.monthEnd")).isTrue();
        assertThat(ADMIN.queryForObject("""
                select aggregate_type || ':' || aggregate_id from hr.outbox_event
                 where tenant_id = ? and event_type = 'people.HeadcountSnapshotTaken'""", String.class, w.tenant()))
                .isEqualTo("legal_entity:" + w.le());
        ADMIN.update("update hr.employment set status = 'SUSPENDED' where id = ?", w.emp().employmentId());
        assertThat(daily(w, monthEnd.plusDays(1)).headcountSnapshots()).isEqualTo(1);
        String next = events(w.tenant(), "people.HeadcountSnapshotTaken").getLast();
        assertThat((Integer) JsonPath.read(next, "$.activeCount")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(next, "$.suspendedCount")).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(next, "$.monthEnd")).isFalse();
    }
}
