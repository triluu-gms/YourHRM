package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Concurrent employment (US-PPL-021/022/023/025): a second employment of the same person in another legal entity,
 * the default PIT primary designation, FIRST_CONTRACT / EACH_EMPLOYER insurance participation and the exception
 * report.
 */
class ConcurrentEmploymentIT extends EmploymentTestSupport {

    @Test
    void anExistingEmployeeIsHiredConcurrentlyIntoAnotherLegalEntity() throws Exception {
        World w = employmentWorld("conc-hire");
        SecondLe le2 = secondLe(w);
        LocalDate d = TODAY.minusDays(1);
        UUID emp1 = w.emp().employmentId();
        UUID emp2 = hireApproved(w, hireBody(existingPerson(w.emp().personId()), le2.le(), le2.unit().id(),
                le2.location(), d, 12_000_000));
        assertThat(employmentStatus(emp2)).isEqualTo("ACTIVE");
        String hired = events(w.tenant(), "people.EmployeeHired").getFirst();
        assertThat((String) JsonPath.read(hired, "$.hireType")).isEqualTo("CONCURRENT");

        // default PIT primary: the employment that was primary until then, from the concurrent hire date
        mvc.perform(get("/api/v1/people/persons/" + w.emp().personId() + "/primary-employment").with(as(w.hr()))
                        .param("asOf", d.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("DESIGNATED"))
                .andExpect(jsonPath("$.primaryEmploymentId").value(emp1.toString()))
                .andExpect(jsonPath("$.liveEmployments").value(2));
        mvc.perform(get("/api/v1/people/persons/" + w.emp().personId() + "/primary-employment").with(as(w.hr()))
                        .param("asOf", d.minusDays(1).toString()))
                .andExpect(jsonPath("$.basis").value("SINGLE_EMPLOYMENT"));
        List<String> primary = events(w.tenant(), "people.PrimaryEmploymentChanged");
        assertThat(primary).hasSize(1);
        assertThat((String) JsonPath.read(primary.getFirst(), "$.reasonCode")).isEqualTo("CONCURRENT_HIRE");
        assertThat((String) JsonPath.read(primary.getFirst(), "$.purpose")).isEqualTo("PIT_FAMILY_DEDUCTION");

        // insurance: FIRST_CONTRACT for SI / HI / UI (the earlier employment), OA_OD at each employer
        mvc.perform(get("/api/v1/people/employments/" + emp1 + "/insurance-participation").with(as(w.hr()))
                        .param("asOf", d.toString()))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].participating").value(true))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].basisRule").value("FIRST_CONTRACT"))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].legalPackVersion").value(1));
        mvc.perform(get("/api/v1/people/employments/" + emp2 + "/insurance-participation").with(as(w.hr()))
                        .param("asOf", d.toString()))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].participating").value(false))
                .andExpect(jsonPath("$[?(@.insuranceType == 'HI')].participating").value(false))
                .andExpect(jsonPath("$[?(@.insuranceType == 'OA_OD')].participating").value(true))
                .andExpect(jsonPath("$[?(@.insuranceType == 'OA_OD')].basisRule").value("EACH_EMPLOYER"));
        assertThat(events(w.tenant(), "people.InsuranceParticipationChanged")).hasSize(8);

        // a second employment in an LE where the person already works is an assignment change
        postJson(w.hr(), "/api/v1/people/hires", hireBody(existingPerson(w.emp().personId()), le2.le(),
                le2.unit().id(), le2.location(), TODAY.plusDays(30), 9_000_000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("same-legal-entity-employment"));
    }

    @Test
    void theExceptionReportShowsMissingParticipationAndPrimaryGaps() throws Exception {
        World w = employmentWorld("conc-report");
        SecondLe le2 = secondLe(w);
        LocalDate d = TODAY.minusDays(1);
        hireApproved(w, hireBody(existingPerson(w.emp().personId()), le2.le(), le2.unit().id(), le2.location(), d,
                12_000_000));
        String path = "/api/v1/people/reports/concurrent-employment-exceptions";
        mvc.perform(get(path).with(as(w.hr())).param("legalEntityId", le2.le().toString()).param("asOf", d.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personsChecked").value(1))
                .andExpect(jsonPath("$.exceptions", hasSize(0)));
        // the fixture employees of LE1 have no participation rows before the concurrent hire
        mvc.perform(get(path).with(as(w.hr())).param("legalEntityId", w.le().toString())
                        .param("asOf", d.minusDays(3).toString()))
                .andExpect(jsonPath("$.exceptions[?(@.code == 'MISSING_INSURANCE_PARTICIPATION')]", hasSize(8)));
        // primary withdrawn behind the service's back: NO_PRIMARY
        ADMIN.update("update hr.primary_employment set status = 'CANCELLED' where person_id = ?", w.emp().personId());
        mvc.perform(get(path).with(as(w.hr())).param("legalEntityId", le2.le().toString()).param("asOf", d.toString()))
                .andExpect(jsonPath("$.exceptions[0].code").value("NO_PRIMARY"))
                .andExpect(jsonPath("$.exceptions[0].link")
                        .value("/api/v1/people/persons/" + w.emp().personId() + "/primary-employment"));
        // an unknown rule is reported, not guessed
        vn.yourhrm.support.TestLegalParameters.undefine(w.tenant(), RULE);
        mvc.perform(get(path).with(as(w.hr())).param("legalEntityId", le2.le().toString()).param("asOf", d.toString()))
                .andExpect(jsonPath("$.exceptions[?(@.code == 'INSURANCE_RULE_MISSING')]", hasSize(1)));
        // other tenants see nothing
        World other = employmentWorld("conc-report-x");
        mvc.perform(get(path).with(as(other.hr())).param("legalEntityId", le2.le().toString())
                        .param("asOf", d.toString()))
                .andExpect(jsonPath("$.personsChecked").value(0));
    }
}
