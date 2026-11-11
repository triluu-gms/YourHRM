package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.api.AssignmentJobs;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.internal.application.salary.SalaryService;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryTerms;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Agreed compensation (US-PPL-041..046): versions only through an approved decision / appendix, GROSS / NET rules,
 * minimum-wage block and probation checks, compensation permission and masking, audited views with purpose,
 * history with corrections and as-known reads, the opening-balance writer and the daily job.
 */
class SalaryIT extends AssignmentTestSupport {

    @Autowired
    SalaryService salaries;
    @Autowired
    AssignmentJobs jobs;
    @Autowired
    @Qualifier(HrDb.TX)
    PlatformTransactionManager hrTx;

    String base(World w) {
        return "/api/v1/people/employments/" + w.emp().employmentId() + "/salary-records";
    }

    static String request(LocalDate effective, String terms, String extra) {
        return """
                {"actionCode":"INCREASE","effectiveDate":"%s","terms":%s%s}"""
                .formatted(effective, terms, extra == null ? "" : "," + extra);
    }

    static String gross(long base) {
        return """
                {"payBasis":"GROSS","baseSalaryAmount":%d,"siSalaryAmount":%d,
                 "allowances":[{"componentCode":"ALW_LUNCH","amount":730000,"inSiSalary":false}]}""".formatted(base, base);
    }

    /** Opening-balance record from the hire date (the only writer besides approved changes). */
    UUID opening(World w, long base) throws Exception {
        return as(w.hr(), () -> new TransactionTemplate(hrTx).execute(s -> salaries.createInitial(
                w.emp().employmentId(), new SalaryTerms("GROSS", null, BigDecimal.valueOf(base), null, null,
                        BigDecimal.valueOf(base), null, null, null, null,
                        List.of(new Allowance("ALW_PHONE", BigDecimal.valueOf(300_000), false))),
                HIRE, null, null, "OPENING_LOAD"))).salaryRecordId();
    }

    UUID requestAndApprove(World w, String body) throws Exception {
        var r = postJson(w.hr(), base(w) + "/change-requests", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.baseSalaryAmount").doesNotExist());
        UUID request = UUID.fromString(read(r, "$.requestId"));
        decide(w.approver(), request, "APPROVE");
        assertThat(callbackStatus(request)).isEqualTo("ACKED");
        return request;
    }

    @Test
    void aSalaryChangesOnlyThroughAnApprovedDecisionAndIsPublishedWithAmounts() throws Exception {
        World w = world("sal-change");
        opening(w, 8_000_000);
        List<String> initial = events(w.tenant(), "people.SalaryChanged");
        assertThat(initial).hasSize(1);
        assertThat((Boolean) JsonPath.read(initial.getFirst(), "$.initialLoad")).isTrue();
        // no direct edit API
        mvc.perform(post(base(w)).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON).content(gross(9_000_000)))
                .andExpect(status().is4xxClientError());
        mvc.perform(put(base(w)).with(as(w.hr())).contentType(MediaType.APPLICATION_JSON).content(gross(9_000_000)))
                .andExpect(status().is4xxClientError());

        LocalDate d = TODAY.minusDays(3);
        requestAndApprove(w, request(d, gross(12_000_000), null));

        mvc.perform(get(base(w)).with(as(w.hr())).param("asOf", d.toString()).param("purpose", "Rà soát lương"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseSalaryAmount").value(12_000_000))
                .andExpect(jsonPath("$.actionCode").value("INCREASE"))
                .andExpect(jsonPath("$.decisionNo").exists())
                .andExpect(jsonPath("$.allowances[0].componentCode").value("ALW_LUNCH"))
                .andExpect(jsonPath("$.amountsMasked").value(false));
        mvc.perform(get(base(w)).with(as(w.hr())).param("asOf", d.minusDays(1).toString()))
                .andExpect(jsonPath("$.baseSalaryAmount").value(8_000_000))
                .andExpect(jsonPath("$.validTo").value(d.toString()));
        mvc.perform(get(base(w) + "/history").with(as(w.hr())))
                .andExpect(jsonPath("$", hasSize(3)));

        List<String> changed = events(w.tenant(), "people.SalaryChanged");
        assertThat(changed).hasSize(2);
        String e = changed.get(1);
        assertThat((String) JsonPath.read(e, "$.changeKind")).isEqualTo("CHANGE");
        assertThat(((Number) JsonPath.read(e, "$.baseSalaryAmount")).longValue()).isEqualTo(12_000_000L);
        assertThat((String) JsonPath.read(e, "$.decisionNo")).isNotBlank();
        assertThat((String) JsonPath.read(e, "$.supersededRecordId")).isNotBlank();
        assertThat(ADMIN.queryForObject("select count(*) from hr.decision where employment_id = ? and decision_type_code = 'SALARY_CHANGE'",
                Integer.class, w.emp().employmentId())).isEqualTo(1);
    }

    @Test
    void grossNetAndMinimumWageRulesAreEnforced() throws Exception {
        World w = world("sal-rules");
        opening(w, 8_000_000);
        LocalDate d = TODAY.minusDays(1);
        String path = base(w) + "/change-requests";
        postJson(w.hr(), path, request(d, "{\"payBasis\":\"NET\",\"baseSalaryAmount\":10000000}", null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("agreed-net-required"));
        postJson(w.hr(), path, request(d, "{\"payBasis\":\"GROSS\",\"baseSalaryAmount\":10000000,"
                + "\"netGuaranteedScope\":[\"BASE\"]}", null))
                .andExpect(jsonPath("$.code").value("net-scope-only-for-net"));
        postJson(w.hr(), path, request(d, "{\"baseSalaryAmount\":10000000,\"currencyCode\":\"USD\"}", null))
                .andExpect(jsonPath("$.code").value("currency-not-supported"));
        postJson(w.hr(), path, request(d, "{\"baseSalaryAmount\":4000000}", null))
                .andExpect(jsonPath("$.code").value("below-minimum-wage"))
                .andExpect(jsonPath("$.region").value("I"));
        postJson(w.hr(), path, request(d, "{\"baseSalaryAmount\":10000000,\"probationPct\":80}", null))
                .andExpect(jsonPath("$.code").value("probation-pct-below-floor"));
        postJson(w.hr(), path, request(d, "{\"baseSalaryAmount\":5000000,\"probationPct\":90}", null))
                .andExpect(jsonPath("$.code").value("probation-below-minimum-wage"));
        postJson(w.hr(), path, request(d, "{\"baseSalaryAmount\":10000000,\"allowances\":["
                + "{\"componentCode\":\"A\",\"amount\":1},{\"componentCode\":\"A\",\"amount\":2}]}", null))
                .andExpect(jsonPath("$.code").value("invalid-allowance"));
        // NET: checked on the base salary while Payroll's simulator is absent, with a warning
        postJson(w.hr(), path, request(d, "{\"payBasis\":\"NET\",\"baseSalaryAmount\":15000000,"
                + "\"agreedNetAmount\":13000000,\"netGuaranteedScope\":[\"BASE\"]}", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0].code").value("gross-up-unavailable"));
        // a version already starting on the date must be corrected instead
        postJson(w.hr(), path, request(HIRE, gross(9_000_000), null))
                .andExpect(jsonPath("$.code").value("salary-version-exists"));
    }

    @Test
    void aCorrectionKeepsTheSupersededVersionAndAsKnownShowsWhatWasKnown() throws Exception {
        World w = world("sal-correct");
        UUID first = opening(w, 8_000_000);
        String knownBefore = ADMIN.queryForObject(
                "select to_char(now() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')", String.class);
        requestAndApprove(w, """
                {"correctsRecordId":"%s","correctionReason":"Nhập sai lương đầu kỳ","terms":%s}"""
                .formatted(first, gross(8_500_000)));
        mvc.perform(get(base(w)).with(as(w.hr())).param("asOf", HIRE.toString()).param("purpose", "Kiểm tra"))
                .andExpect(jsonPath("$.baseSalaryAmount").value(8_500_000))
                .andExpect(jsonPath("$.correctionReason").value("Nhập sai lương đầu kỳ"));
        mvc.perform(get(base(w)).with(as(w.hr())).param("asOf", HIRE.toString()).param("asKnown", knownBefore))
                .andExpect(jsonPath("$.baseSalaryAmount").value(8_000_000));
        mvc.perform(get(base(w) + "/history").with(as(w.hr())))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first.toString()))
                .andExpect(jsonPath("$[0].knownTo").exists())
                .andExpect(jsonPath("$[0].correctionReason").value("Nhập sai lương đầu kỳ"));
        assertThat((String) JsonPath.read(events(w.tenant(), "people.SalaryChanged").get(1), "$.changeKind"))
                .isEqualTo("CORRECTION");
    }

    @Test
    void viewsNeedCompensationPermissionArePurposedAuditedAndMasked() throws Exception {
        World w = world("sal-access");
        World other = world("sal-access-b");
        opening(w, 8_000_000);
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get(base(w)).with(as(viewer)).param("purpose", "x")).andExpect(status().isForbidden());
        TestUser cb = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.COMPENSATION_VIEW);
        // purpose on the first view of the session, then remembered
        mvc.perform(get(base(w)).with(as(cb))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("purpose-required"));
        mvc.perform(get(base(w)).with(as(cb)).param("purpose", "Tính thưởng")).andExpect(status().isOk());
        mvc.perform(get(base(w) + "/history").with(as(cb))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/people/salary-records").with(as(cb)))
                // numeric(19,4) amounts arrive as decimals through a JSONPath filter (a list of doubles)
                .andExpect(jsonPath("$[?(@.employmentId=='" + w.emp().employmentId() + "')].salary.baseSalaryAmount")
                        .value(8_000_000.0));
        List<String> purposes = ADMIN.queryForList("""
                select change->>'purpose' from audit.audit_event
                 where tenant_id = ? and action = 'VIEW_SALARY' and object_id = ? and actor_id = ?""", String.class,
                w.tenant(), w.emp().employmentId(), cb.userId());
        assertThat(purposes).hasSize(3).containsOnly("Tính thưởng");
        assertThat(ADMIN.queryForList("select distinct change->>'view' from audit.audit_event where tenant_id = ? "
                + "and action = 'VIEW_SALARY' and actor_id = ?", String.class, w.tenant(), cb.userId()))
                .containsExactlyInAnyOrder("AS_OF", "HISTORY", "LIST");
        // a grant masked for pay sees no amounts
        TestUser masked = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.COMPENSATION_VIEW);
        ADMIN.update("""
                update core.role_permission set field_mask_level = 'PII_AND_PAY'
                 where tenant_id = ? and role_id = (select role_id from core.role_assignment where user_id = ?)""",
                w.tenant(), masked.userId());
        mvc.perform(get(base(w)).with(as(masked)).param("purpose", "Kiểm tra"))
                .andExpect(jsonPath("$.baseSalaryAmount").doesNotExist())
                .andExpect(jsonPath("$.amountsMasked").value(true));
        // scope and tenant
        TestUser otherUnit = PPL.userWith(w.tenant(), "ORG_UNIT", List.of(w.unit2().id()),
                PeoplePermissions.COMPENSATION_VIEW, PeoplePermissions.COMPENSATION_UPDATE);
        mvc.perform(get(base(w)).with(as(otherUnit)).param("purpose", "x")).andExpect(status().isNotFound());
        postJson(otherUnit, base(w) + "/change-requests", request(TODAY, gross(9_000_000), null))
                .andExpect(status().isNotFound());
        mvc.perform(get(base(other)).with(as(w.hr())).param("purpose", "x")).andExpect(status().isNotFound());
        // changing needs hr.compensation.update
        postJson(cb, base(w) + "/change-requests", request(TODAY, gross(9_000_000), null))
                .andExpect(status().isForbidden());
    }

    @Test
    void futureSalaryChangesArePublishedByTheDailyJob() throws Exception {
        World w = world("sal-future");
        opening(w, 8_000_000);
        LocalDate d = TODAY.plusDays(15);
        var r = postJson(w.hr(), base(w) + "/change-requests", request(d, gross(11_000_000), null));
        UUID request = UUID.fromString(read(r, "$.requestId"));
        decide(w.approver(), request, "APPROVE");
        assertThat(events(w.tenant(), "people.SalaryChanged")).hasSize(1);
        assertThat(as(w.hr(), () -> jobs.publishDueChanges(d)).salaryChanges()).isEqualTo(1);
        assertThat(as(w.hr(), () -> jobs.publishDueChanges(d)).salaryChanges()).isZero();
        assertThat((String) JsonPath.read(events(w.tenant(), "people.SalaryChanged").get(1), "$.effectiveDate"))
                .isEqualTo(d.toString());
    }
}
