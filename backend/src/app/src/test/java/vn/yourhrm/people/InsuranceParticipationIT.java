package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.shared.common.legal.LegalParameterPackPublished;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Insurance participation (US-PPL-023 [high-uncertainty]): the rule comes only from {@code
 * insurance.multi_contract_rule}; a new pack switching HI to HIGHEST_SALARY re-derives; the salary tie rule; HR
 * overrides with a reason.
 */
class InsuranceParticipationIT extends EmploymentTestSupport {

    String rows(UUID employment, LocalDate asOf) {
        return "/api/v1/people/employments/" + employment + "/insurance-participation?asOf=" + asOf;
    }

    @Test
    void aNewPackSwitchingHealthInsuranceToHighestSalaryIsAParameterChangeOnly() throws Exception {
        World w = employmentWorld("ins-pack");
        SecondLe le2 = secondLe(w);
        LocalDate d = TODAY.minusDays(1);
        UUID emp1 = w.emp().employmentId();
        // emp1 (fixture) has no salary record: under HIGHEST_SALARY the employment with a salary ranks first
        UUID emp2 = hireApproved(w, hireBody(existingPerson(w.emp().personId()), le2.le(), le2.unit().id(),
                le2.location(), d, 15_000_000));
        mvc.perform(get(rows(emp2, d)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'HI')].participating").value(false));

        TestLegalParameters.define(w.tenant(), RULE,
                Map.of("SI", "FIRST_CONTRACT", "HI", "HIGHEST_SALARY", "UI", "FIRST_CONTRACT", "OA_OD", "EACH_EMPLOYER"));
        int changed = as(w.hr(), () -> jobs.onLegalPackPublished(new LegalParameterPackPublished(2, 1, Set.of(RULE),
                List.of(d))));
        // the employee's lines change; the manager (fixture without rows) gets its missing rows derived too
        assertThat(changed).isEqualTo(2);
        mvc.perform(get(rows(emp2, d)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'HI')].participating").value(true))
                .andExpect(jsonPath("$[?(@.insuranceType == 'HI')].basisRule").value("HIGHEST_SALARY"))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].participating").value(false));
        mvc.perform(get(rows(emp1, d)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'HI')].participating").value(false));
        String last = events(w.tenant(), "people.InsuranceParticipationChanged").getLast();
        assertThat((String) JsonPath.read(last, "$.trigger")).isEqualTo("LEGAL_PACK_PUBLISHED");
        // a pack that does not touch the rule changes nothing; the same pack again is idempotent
        assertThat(as(w.hr(), () -> jobs.onLegalPackPublished(new LegalParameterPackPublished(3, 2,
                Set.of("other.key"), List.of(d))))).isZero();
        assertThat(as(w.hr(), () -> jobs.onLegalPackPublished(new LegalParameterPackPublished(2, 1, Set.of(RULE),
                List.of(d))))).isZero();
    }

    @Test
    void equalSalariesFallBackToTheFirstContractOrder() throws Exception {
        World w = employmentWorld("ins-tie");
        TestLegalParameters.define(w.tenant(), RULE,
                Map.of("SI", "HIGHEST_SALARY", "HI", "HIGHEST_SALARY", "UI", "HIGHEST_SALARY", "OA_OD", "EACH_EMPLOYER"));
        SecondLe le2 = secondLe(w);
        // two new hires of one new person, same salary: the earlier hire date wins
        UUID first = hireApproved(w, hireBody(newPerson("001095000201"), w.le(), w.unit().id(), w.location(),
                TODAY.minusDays(10), 10_000_000));
        UUID person = ADMIN.queryForObject("select person_id from hr.employment where id = ?", UUID.class, first);
        UUID second = hireApproved(w, hireBody(existingPerson(person), le2.le(), le2.unit().id(), le2.location(),
                TODAY.minusDays(5), 10_000_000));
        mvc.perform(get(rows(first, TODAY)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].participating").value(true));
        mvc.perform(get(rows(second, TODAY)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].participating").value(false))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].basisRule").value("HIGHEST_SALARY"));
    }

    @Test
    void hrOverridesNeedAReasonAndShiftTheSinglePayer() throws Exception {
        World w = employmentWorld("ins-override");
        SecondLe le2 = secondLe(w);
        LocalDate d = TODAY.minusDays(1);
        UUID emp1 = w.emp().employmentId();
        UUID emp2 = hireApproved(w, hireBody(existingPerson(w.emp().personId()), le2.le(), le2.unit().id(),
                le2.location(), d, 12_000_000));
        String path = "/api/v1/people/employments/" + emp1 + "/insurance-participation/overrides";
        postJson(w.hr(), path, "{\"insuranceType\":\"SI\",\"validFrom\":\"%s\",\"participating\":false}".formatted(d))
                .andExpect(status().isBadRequest());
        postJson(w.hr(), path, """
                {"insuranceType":"SI","validFrom":"%s","participating":false,"exemptionReasonCode":"PENSIONER",
                 "overrideReason":"Đang hưởng lương hưu"}""".formatted(d))
                .andExpect(status().isOk());
        mvc.perform(get(rows(emp1, d)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].participating").value(false))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].manualOverride").value(true))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].exemptionReasonCode").value("PENSIONER"));
        mvc.perform(get(rows(emp2, d)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].participating").value(true));
        // the override survives a re-derivation
        postJson(w.hr(), "/api/v1/people/employments/" + emp2 + "/insurance-participation/rederive", "")
                .andExpect(status().isOk());
        mvc.perform(get(rows(emp1, d)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'SI')].manualOverride").value(true));
        // forcing another payer under a single-payer rule takes the payer role away from the derived one
        postJson(w.hr(), "/api/v1/people/employments/" + emp2 + "/insurance-participation/overrides", """
                {"insuranceType":"HI","validFrom":"%s","participating":true,"overrideReason":"x"}""".formatted(d))
                .andExpect(status().isOk());
        mvc.perform(get(rows(emp1, d)).with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.insuranceType == 'HI')].participating").value(false));
    }
}
