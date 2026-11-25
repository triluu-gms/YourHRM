package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.people.api.AssignmentJobs;

/**
 * PM decision D3 (2026-11-19): dated employee category / working regime ({@code hr.employment_classification}),
 * {@code people.EmploymentChanged} per version (when saved if its date has come, else from the daily job), and
 * {@code people.AssignmentChanged} v2 carrying the values in force on its start.
 */
class EmploymentClassificationIT extends EmploymentTestSupport {

    @Autowired
    AssignmentJobs assignmentJobs;

    int version(World w, UUID employment) throws Exception {
        String b = mvc.perform(get("/api/v1/people/employments/" + employment).with(as(w.hr())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(b, "$.version");
    }

    ResultActions change(World w, UUID employment, String category, String regime, LocalDate from) throws Exception {
        String body = """
                {"version":%d,"workerCategoryCode":"EMPLOYEE","employeeCategoryCode":%s,"workingRegimeCode":%s%s}"""
                .formatted(version(w, employment), category == null ? "null" : "\"" + category + "\"",
                        regime == null ? "null" : "\"" + regime + "\"",
                        from == null ? "" : ",\"classificationFrom\":\"" + from + "\"");
        return mvc.perform(put("/api/v1/people/employments/" + employment).with(as(w.hr()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    static List<String> history(UUID employment) {
        return ADMIN.queryForList("""
                select coalesce(employee_category_code, '-') || '/' || coalesce(working_regime_code, '-') || ' '
                       || valid_from || '..' || coalesce(valid_to::text, '-')
                  from hr.employment_classification where employment_id = ? and status = 'ACTIVE'
                 order by valid_from""", String.class, employment);
    }

    static Map<String, Object> current(UUID employment) {
        return ADMIN.queryForMap("select employee_category_code as c, working_regime_code as r from hr.employment"
                + " where id = ?", employment);
    }

    @Test
    void aBackDatedChangeKeepsTheEarlierDaysAndIsPublishedAtOnce() throws Exception {
        World w = employmentWorld("ppl-class-back");
        UUID e = w.emp().employmentId();
        LocalDate from = HIRE.plusMonths(3);
        assertThat(history(e)).isEmpty();   // data written before v0.7

        change(w, e, "WORKER", "SHIFT", from).andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCategoryCode").value("WORKER"));

        // the days before the change keep the earlier (empty) values: a baseline version from the hire date
        assertThat(history(e)).containsExactly("-/- " + HIRE + ".." + from, "WORKER/SHIFT " + from + "..-");
        assertThat(current(e)).containsEntry("c", "WORKER").containsEntry("r", "SHIFT");
        assertThat(events(w.tenant(), "people.EmploymentChanged")).singleElement().satisfies(p -> assertThat(p)
                .contains("\"employeeCategoryCode\": \"WORKER\"", "\"workingRegimeCode\": \"SHIFT\"",
                        "\"validFrom\": \"" + from + "\"", "\"validTo\": null",
                        "\"previousEmployeeCategoryCode\": null"));

        // a correction inside the first months is bounded by the next version
        LocalDate earlier = HIRE.plusMonths(1);
        change(w, e, "TRAINEE", "OFFICE", earlier).andExpect(status().isOk());
        assertThat(history(e)).containsExactly("-/- " + HIRE + ".." + earlier,
                "TRAINEE/OFFICE " + earlier + ".." + from, "WORKER/SHIFT " + from + "..-");
        assertThat(current(e)).containsEntry("c", "WORKER");
        assertThat(events(w.tenant(), "people.EmploymentChanged")).hasSize(2);

        // the same values again: no new version, no event
        change(w, e, "WORKER", "SHIFT", from).andExpect(status().isOk());
        assertThat(events(w.tenant(), "people.EmploymentChanged")).hasSize(2);

        mvc.perform(get("/api/v1/people/employments/" + e + "/classifications").with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].employeeCategoryCode").value("WORKER"))
                .andExpect(jsonPath("$[2].validFrom").value(from.toString()));

        // not before the hire date
        change(w, e, "STAFF", "OFFICE", HIRE.minusDays(1)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("classification-before-hire"));
    }

    @Test
    void aFutureChangeWaitsForTheDailyJob() throws Exception {
        World w = employmentWorld("ppl-class-future");
        UUID e = w.emp().employmentId();
        change(w, e, "WORKER", "SHIFT", null).andExpect(status().isOk());   // from today
        assertThat(events(w.tenant(), "people.EmploymentChanged")).hasSize(1);

        LocalDate later = TODAY.plusDays(10);
        change(w, e, "STAFF", "OFFICE", later).andExpect(status().isOk());
        assertThat(history(e)).endsWith("WORKER/SHIFT " + TODAY + ".." + later, "STAFF/OFFICE " + later + "..-");
        // recorded, not in force yet: hr.employment keeps today's values and nothing is published
        assertThat(current(e)).containsEntry("c", "WORKER");
        assertThat(events(w.tenant(), "people.EmploymentChanged")).hasSize(1);

        assertThat(as(w.hr(), () -> assignmentJobs.publishDueChanges(later.minusDays(1))).employmentChanges())
                .isZero();
        assertThat(as(w.hr(), () -> assignmentJobs.publishDueChanges(later)).employmentChanges()).isEqualTo(1);
        assertThat(as(w.hr(), () -> assignmentJobs.publishDueChanges(later)).employmentChanges()).isZero();
        List<String> published = events(w.tenant(), "people.EmploymentChanged");
        assertThat(published).hasSize(2);
        assertThat(published.getLast()).contains("\"employeeCategoryCode\": \"STAFF\"",
                "\"previousEmployeeCategoryCode\": \"WORKER\"", "\"validFrom\": \"" + later + "\"");
    }

    @Test
    void aHireStartsTheHistoryAndAssignmentChangedV2CarriesTheValues() throws Exception {
        World w = employmentWorld("ppl-class-hire");
        String body = hireBody(newPerson("001095000771"), w.le(), w.unit().id(), w.location(), TODAY, 15_000_000L)
                .replace("\"workerCategoryCode\":\"EMPLOYEE\"", "\"workerCategoryCode\":\"EMPLOYEE\","
                        + "\"employeeCategoryCode\":\"WORKER\",\"workingRegimeCode\":\"SHIFT\"");
        UUID e = hireApproved(w, body);
        assertThat(history(e)).containsExactly("WORKER/SHIFT " + TODAY + "..-");
        List<Map<String, Object>> rows = ADMIN.queryForList("""
                select schema_version, payload::text as payload from hr.outbox_event
                 where tenant_id = ? and event_type = 'people.AssignmentChanged' and employment_id = ?""",
                w.tenant(), e);
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            assertThat(((Number) r.get("schema_version")).intValue()).isEqualTo(2);
            assertThat(r.get("payload").toString()).contains("\"employeeCategoryCode\": \"WORKER\"",
                    "\"workingRegimeCode\": \"SHIFT\"");
        });
    }
}
