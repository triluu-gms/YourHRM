package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.separation.SeparationKeys;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * Separation records (BR-PPL-070..074; US-PPL-060..064, 067): legal grounds and sub-reasons, the HR flow from draft to
 * approval with the termination decision and {@code people.TerminationScheduled}, the notice rules (employee and
 * employer side, special trades, no-notice cases, working days), the protected-employee block with its documented
 * exception, the OD-8 personal e-mail, service periods, scope and tenant isolation.
 */
class SeparationIT extends SeparationTestBase {

    @Test
    void legalGroundsCarryStatutoryCodesAndTenantsOnlyAddSubReasons() throws Exception {
        World w = world("sep-reasons");
        ADMIN.update("""
                insert into core.setting_value (tenant_id, setting_key, value)
                values (?, 'hr.separation.sub_reasons',
                        '[{"code":"BETTER_OFFER","groundCode":"EMPLOYEE_RESIGNATION","nameVi":"Có việc tốt hơn"},
                          {"code":"MY_GROUND","groundCode":"MY_OWN_GROUND","nameVi":"Căn cứ riêng"}]')""", w.tenant());

        mvc.perform(get("/api/v1/people/separations/reasons").with(as(w.hr()))).andDo(FAIL_ON_500)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounds", hasSize(11)))
                .andExpect(jsonPath("$.grounds[?(@.code=='EMPLOYEE_RESIGNATION')].statutoryCode").value("TV-NLD"))
                .andExpect(jsonPath("$.grounds[?(@.code=='REDUNDANCY')].severanceType").value("JOB_LOSS_ALLOWANCE"))
                .andExpect(jsonPath("$.grounds[?(@.code=='DISMISSAL')].employerInitiated").value(true))
                .andExpect(jsonPath("$.grounds[*].code", not(hasItem("MY_OWN_GROUND"))))
                .andExpect(jsonPath("$.subReasons[?(@.code=='BETTER_OFFER')].problems[0]").doesNotExist())
                .andExpect(jsonPath("$.subReasons[?(@.code=='MY_GROUND')].problems[0]").exists());

        create(w.hr(), w.emp().employmentId(), attrs("MY_OWN_GROUND", "2026-01-02", "2026-02-20", null))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-legal-ground"));
        create(w.hr(), w.emp().employmentId(), attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-20",
                "\"reasonCode\":\"BETTER_OFFER\""))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-separation-reason"));
        create(w.hr(), w.emp().employmentId(), attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-20",
                "\"reasonCode\":\"MY_GROUND\""))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-separation-reason"));
        create(w.hr(), w.emp().employmentId(), attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-20",
                "\"reasonCode\":\"BETTER_OFFER\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.separation.reasonCode").value("BETTER_OFFER"))
                .andExpect(jsonPath("$.separation.separationType").value("RESIGNATION"))
                .andExpect(jsonPath("$.separation.initiatedBy").value("EMPLOYEE"));

        // a legal pack without the ground refuses the operation (fail closed)
        World x = world("sep-reasons-pack");
        TestLegalParameters.define(x.tenant(), SeparationKeys.LEGAL_GROUNDS, Map.of());
        create(x.hr(), x.emp().employmentId(), attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-20", null))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("legal-parameter-missing"));
    }

    @Test
    void anHrSeparationIsApprovedWithADecisionAndScheduledWithTheShortfall() throws Exception {
        World w = world("sep-flow");
        UUID emp = w.emp().employmentId();
        var created = create(w.hr(), emp, attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-01", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.separation.status").value("DRAFT"))
                .andExpect(jsonPath("$.notice.side").value("EMPLOYEE"))
                .andExpect(jsonPath("$.notice.category").value("INDEFINITE"))
                .andExpect(jsonPath("$.notice.requiredDays").value(45))
                .andExpect(jsonPath("$.notice.givenDays").value(30))
                .andExpect(jsonPath("$.notice.shortfallDays").value(15))
                .andExpect(jsonPath("$.notice.consequences", hasItem("NO_SEVERANCE")))
                .andExpect(jsonPath("$.separation.noticeShortfallDays").value(15))
                .andExpect(jsonPath("$.separation.severanceType").value("SEVERANCE_ALLOWANCE"))
                .andExpect(jsonPath("$.separation.severanceEligibility").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.separation.terminationDate").value("2026-02-01"))
                .andExpect(jsonPath("$.separation.accessRevocationDate").value("2026-02-01"))
                .andExpect(jsonPath("$.separation.accessRevocationAt").value("2026-02-01T23:59:00+07:00"))
                .andExpect(jsonPath("$.separation.servicePeriods[0].periodKind").value("WORKED"))
                .andExpect(jsonPath("$.separation.servicePeriods[0].periodFrom").value("2024-01-01"))
                .andExpect(jsonPath("$.separation.servicePeriods[0].periodTo").value("2026-02-01"))
                .andExpect(jsonPath("$.separation.servicePeriods[0].derived").value(true))
                .andExpect(jsonPath("$.statutoryCode").value("TV-NLD"))
                .andExpect(jsonPath("$.warnings[0]").exists());
        UUID id = separationId(created);

        // one live separation per employment
        create(w.hr(), emp, attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-01", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("separation-exists"));

        // OD-8: a personal e-mail is required unless HR records "no e-mail"
        submit(w.hr(), id, null).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("personal-email-required"));
        submit(w.hr(), id, "\"personalEmail\":\"An.Nguyen@Gmail.com\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.separation.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.separation.workflowInstanceId").exists());
        assertThat(ADMIN.queryForObject("""
                select contact_value from hr.person_contact where person_id = ? and contact_type = 'PERSONAL_EMAIL'
                   and is_primary""", String.class, w.emp().personId())).isEqualTo("an.nguyen@gmail.com");
        assertThat(events(w.tenant(), "people.TerminationScheduled")).isEmpty();

        decide(w.approver(), id, "APPROVE");
        mvc.perform(get("/api/v1/people/separations/" + id).with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.employmentPhase").value("NOTICE"))
                .andExpect(jsonPath("$.employmentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.decisionId").exists());
        Map<String, Object> decision = ADMIN.queryForMap("""
                select d.decision_type_code, d.status, d.decision_no, d.effective_date from hr.decision d
                  join hr.separation s on s.tenant_id = d.tenant_id and s.decision_id = d.id where s.id = ?""", id);
        assertThat(decision).containsEntry("decision_type_code", "TERMINATION").containsEntry("status", "APPROVED")
                .containsEntry("effective_date", Date.valueOf("2026-02-01"));
        assertThat((String) decision.get("decision_no")).contains("QĐ");
        List<Map<String, Object>> scheduled = events(w.tenant(), "people.TerminationScheduled");
        assertThat(scheduled).hasSize(1);
        String payload = (String) scheduled.getFirst().get("payload");
        assertThat(payload).contains("\"noticeShortfallDays\":15").contains("\"statutoryCode\":\"TV-NLD\"")
                .contains("\"legalGroundCode\":\"EMPLOYEE_RESIGNATION\"").contains("\"formerEmployeeEmailOnFile\":true")
                .contains("\"lastWorkingDate\":\"2026-02-01\"").contains("\"accessRevocationDate\":\"2026-02-01\"")
                .contains("\"protectedEmployee\":false").contains("NO_SEVERANCE").doesNotContain("gmail");
        assertThat(ADMIN.queryForObject("select status from hr.employment where id = ?", String.class, emp))
                .isEqualTo("ACTIVE");

        // the employee directory links the record; the employment lists it
        mvc.perform(get("/api/v1/people/employments/" + emp + "/separation").with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/api/v1/people/employments/" + emp + "/separations").with(as(w.hr())))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/people/separations").param("status", "SCHEDULED").with(as(w.hr())))
                .andExpect(jsonPath("$[*].id", hasItem(id.toString())));
        // the decision template of terminations offers the separation fields
        mvc.perform(get("/api/v1/core/document-template-definitions").with(as(w.hr())))
                .andExpect(jsonPath("$[?(@.code == 'HR_DECISION_TERMINATION')].mergeFields[*].key",
                        hasItem("separation.ground_vi")));
    }

    @Test
    void aRejectedRequestIsClosedAndADraftCanBeEditedOrCancelled() throws Exception {
        World w = world("sep-reject");
        UUID emp = w.emp().employmentId();
        UUID id = separationId(create(w.hr(), emp, attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-01", null)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/people/separations/" + id).with(as(w.hr()))
                        .contentType("application/json")
                        .content("{\"version\":" + version(id) + ",\"attributes\":"
                                + attrs("MUTUAL_AGREEMENT", "2026-01-05", "2026-03-31", "\"rehireEligible\":true")
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.separation.lastWorkingDate").value("2026-03-31"))
                .andExpect(jsonPath("$.separation.rehireEligible").value(true))
                .andExpect(jsonPath("$.separation.servicePeriods[0].periodTo").value("2026-03-31"))
                .andExpect(jsonPath("$.notice.side").value("NONE"))
                .andExpect(jsonPath("$.separation.severanceEligibility").value("ELIGIBLE"));
        submit(w.hr(), id, "\"noPersonalEmail\":true").andExpect(status().isOk());
        decide(w.approver(), id, "REJECT");
        assertThat(row(id)).containsEntry("status", "REJECTED");

        UUID second = separationId(create(w.hr(), emp, attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-01", null))
                .andExpect(status().isCreated()));
        postJson(w.hr(), "/api/v1/people/separations/" + second + "/cancel",
                "{\"version\":" + version(second) + ",\"reason\":\"entered by mistake\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(events(w.tenant(), "people.TerminationCancelled")).isEmpty();
    }

    @Test
    void theNoticeInputsTheNoEmailFlagAndTheSignatoryAreStoredAndUsedOnEveryLaterStep() throws Exception {
        World w = world("sep-stored");
        // a no-notice case (exempt): no shortfall, severance kept
        UUID id = separationId(create(w.hr(), w.emp().employmentId(), attrs("EMPLOYEE_RESIGNATION", "2026-01-02",
                "2026-02-01", "\"noticeExemptReasonCode\":\"NOT_PAID\",\"signatoryName\":\"Lê Văn Giám\","
                        + "\"signatoryTitle\":\"Giám đốc\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notice.requiredDays").value(0))
                .andExpect(jsonPath("$.separation.noticeExemptReasonCode").value("NOT_PAID"))
                .andExpect(jsonPath("$.separation.signatoryName").value("Lê Văn Giám"))
                .andExpect(jsonPath("$.separation.noPersonalEmail").value(false)));
        assertThat(ADMIN.queryForMap("""
                select notice_exempt_reason_code, special_trade_code, signatory_name, signatory_title, no_personal_email
                  from hr.separation where id = ?""", id))
                .containsEntry("notice_exempt_reason_code", "NOT_PAID").containsEntry("special_trade_code", null)
                .containsEntry("signatory_name", "Lê Văn Giám").containsEntry("signatory_title", "Giám đốc")
                .containsEntry("no_personal_email", false);

        // the submission recomputes the notice with the stored exemption; HR's "no e-mail" is stored
        submit(w.hr(), id, "\"noPersonalEmail\":true,\"note\":\"HR note\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.notice.requiredDays").value(0))
                .andExpect(jsonPath("$.notice.shortfallDays").value(0))
                .andExpect(jsonPath("$.separation.severanceEligibility").value("ELIGIBLE"))
                .andExpect(jsonPath("$.separation.noPersonalEmail").value(true));
        assertThat(ADMIN.queryForMap("""
                select no_personal_email, employee_note, required_notice_days, notice_shortfall_days
                  from hr.separation where id = ?""", id))
                .containsEntry("no_personal_email", true).containsEntry("employee_note", null);

        // approval: the decision carries the stored signatory; no e-mail is needed (declared)
        decide(w.approver(), id, "APPROVE");
        assertThat(ADMIN.queryForMap("""
                select s.status, d.signatory_name, d.signatory_title from hr.separation s
                  join hr.decision d on d.tenant_id = s.tenant_id and d.id = s.decision_id where s.id = ?""", id))
                .containsEntry("status", "SCHEDULED").containsEntry("signatory_name", "Lê Văn Giám")
                .containsEntry("signatory_title", "Giám đốc");
        assertThat((String) events(w.tenant(), "people.TerminationScheduled").getFirst().get("payload"))
                .contains("\"formerEmployeeEmailOnFile\":false").contains("\"noticeShortfallDays\":0");

        // a special trade is stored and recomputed at submission too
        Employee crew = PPL.employee(w.tenant(), w.le(), w.unit(), "Trần", "Bình", LocalDate.of(2024, 1, 1));
        contract(w.tenant(), crew.employmentId(), "INDEFINITE", LocalDate.of(2024, 1, 1), null);
        UUID trade = separationId(create(w.hr(), crew.employmentId(), attrs("EMPLOYEE_RESIGNATION", "2026-01-02",
                "2026-02-01", "\"specialTradeCode\":\"AVIATION_CREW\"")).andExpect(status().isCreated()));
        assertThat(ADMIN.queryForObject("select special_trade_code from hr.separation where id = ?", String.class, trade))
                .isEqualTo("AVIATION_CREW");
        submit(w.hr(), trade, "\"personalEmail\":\"binh@example.vn\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.notice.requiredDays").value(120))
                .andExpect(jsonPath("$.notice.shortfallDays").value(90))
                .andExpect(jsonPath("$.separation.noPersonalEmail").value(false));

        // OD-8 on approval: the e-mail was removed after submission and "no e-mail" was not recorded -> not applied
        ADMIN.update("delete from hr.person_contact where person_id = ? and contact_type = 'PERSONAL_EMAIL'",
                crew.personId());
        decide(w.approver(), trade, "APPROVE");
        assertThat(ADMIN.queryForObject("select status from hr.separation where id = ?", String.class, trade))
                .isEqualTo("SUBMITTED");
        assertThat(events(w.tenant(), "people.TerminationScheduled")).hasSize(1);
    }

    @Test
    void noticeFollowsTheContractTheSideSpecialTradesAndNoNoticeCases() throws Exception {
        World w = world("sep-notice");
        String preview = "/api/v1/people/employments/" + w.emp().employmentId() + "/separation/preview";
        postJson(w.hr(), preview, attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-01",
                "\"specialTradeCode\":\"AVIATION_CREW\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notice.requiredDays").value(120))
                .andExpect(jsonPath("$.notice.shortfallDays").value(90));
        postJson(w.hr(), preview, attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-01",
                "\"noticeExemptReasonCode\":\"NOT_PAID\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notice.requiredDays").value(0))
                .andExpect(jsonPath("$.notice.shortfallDays").value(0))
                .andExpect(jsonPath("$.severanceEligibility").value("ELIGIBLE"));
        postJson(w.hr(), preview, attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-01",
                "\"noticeExemptReasonCode\":\"ABSENT_5_DAYS\""))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-notice-exemption"));
        postJson(w.hr(), preview, attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-01",
                "\"specialTradeCode\":\"ASTRONAUT\""))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-special-trade"));
        // the employer side: shortfall means paying wages, severance stays
        postJson(w.hr(), preview, attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-01", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notice.side").value("EMPLOYER"))
                .andExpect(jsonPath("$.notice.shortfallDays").value(15))
                .andExpect(jsonPath("$.notice.consequences[0]").value("PAY_WAGES_FOR_SHORTFALL_DAYS"))
                .andExpect(jsonPath("$.severanceEligibility").value("ELIGIBLE"))
                .andExpect(jsonPath("$.warnings", hasItem(org.hamcrest.Matchers.startsWith("leave-data-unavailable"))));
        postJson(w.hr(), preview, attrs("EMPLOYEE_RESIGNATION", "2026-03-01", "2026-02-01", null))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-notice-date"));

        // a 6-month definite contract: 3 working days (Sunday excluded), a special trade a quarter of the term
        Employee shortTerm = PPL.employee(w.tenant(), w.le(), w.unit(), "Trần", "Bình", LocalDate.of(2026, 1, 1));
        contract(w.tenant(), shortTerm.employmentId(), "DEFINITE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        String p2 = "/api/v1/people/employments/" + shortTerm.employmentId() + "/separation/preview";
        postJson(w.hr(), p2, attrs("EMPLOYEE_RESIGNATION", "2026-03-06", "2026-03-09", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notice.category").value("DEFINITE_SHORT"))
                .andExpect(jsonPath("$.notice.basis").value("WORKING"))
                .andExpect(jsonPath("$.notice.requiredDays").value(3))
                .andExpect(jsonPath("$.notice.givenDays").value(2))
                .andExpect(jsonPath("$.notice.shortfallDays").value(1));
        postJson(w.hr(), p2, attrs("EMPLOYEE_RESIGNATION", "2026-03-06", "2026-03-09",
                "\"specialTradeCode\":\"AVIATION_CREW\""))
                .andExpect(jsonPath("$.notice.requiredDays").value(46));

        // a 24-month definite contract: 30 days; probation: none
        Employee longTerm = PPL.employee(w.tenant(), w.le(), w.unit(), "Lê", "Chi", LocalDate.of(2025, 1, 1));
        contract(w.tenant(), longTerm.employmentId(), "DEFINITE", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
        postJson(w.hr(), "/api/v1/people/employments/" + longTerm.employmentId() + "/separation/preview",
                attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-01", null))
                .andExpect(jsonPath("$.notice.category").value("DEFINITE_LONG"))
                .andExpect(jsonPath("$.notice.requiredDays").value(30))
                .andExpect(jsonPath("$.notice.shortfallDays").value(0));
        Employee trainee = PPL.employee(w.tenant(), w.le(), w.unit(), "Phạm", "Dũng", LocalDate.of(2026, 1, 1));
        contract(w.tenant(), trainee.employmentId(), "PROBATION", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));
        postJson(w.hr(), "/api/v1/people/employments/" + trainee.employmentId() + "/separation/preview",
                attrs("EMPLOYEE_RESIGNATION", "2026-02-10", "2026-02-11", null))
                .andExpect(jsonPath("$.notice.category").value("PROBATION"))
                .andExpect(jsonPath("$.notice.requiredDays").value(0));
    }

    @Test
    void employerTerminationOfAProtectedEmployeeNeedsADocumentedApprovedException() throws Exception {
        World w = world("sep-protected");
        ADMIN.update("""
                insert into hr.special_status (tenant_id, id, person_id, status_type, valid_from)
                values (?, ?, ?, 'PREGNANCY', ?)""", w.tenant(), Ids.newId(), w.emp().personId(),
                Date.valueOf("2025-12-01"));
        UUID emp = w.emp().employmentId();
        create(w.hr(), emp, attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-20", null))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("protected-employee"))
                .andExpect(jsonPath("$.reasons[0]").value("PREGNANCY"));
        create(w.hr(), emp, attrs("DISMISSAL", "2026-01-02", "2026-02-20", null))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("protected-employee"));
        // a resignation by the employee is not blocked, only flagged
        postJson(w.hr(), "/api/v1/people/employments/" + emp + "/separation/preview",
                attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-20", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.protection.protectedEmployee").value(true))
                .andExpect(jsonPath("$.protection.exceptionRequired").value(false));
        // an HR user without the special-status view does not learn the sensitive type
        TestUser limited = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.SEPARATION_CREATE);
        create(limited, emp, attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-20", null))
                .andExpect(status().is(422)).andExpect(jsonPath("$.reasons[0]").value("SPECIAL_STATUS"));

        UUID id = separationId(create(w.hr(), emp, attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-20",
                "\"protectedOverrideReason\":\"Doanh nghiệp chấm dứt hoạt động (Điều 37.3)\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.separation.protectedEmployee").value(true))
                .andExpect(jsonPath("$.protection.exceptionRequired").value(true))
                .andExpect(jsonPath("$.protection.leaveDataAvailable").value(false)));
        submit(w.hr(), id, "\"noPersonalEmail\":true").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("exception-evidence-required"));
        mvc.perform(multipart("/api/v1/people/separations/" + id + "/evidence")
                        .file(new MockMultipartFile("file", "closure.pdf", "application/pdf",
                                "%PDF-1.4 closure decision".getBytes(StandardCharsets.US_ASCII)))
                        .with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.protectedOverrideDocumentId").exists());
        submit(w.hr(), id, "\"noPersonalEmail\":true").andExpect(status().isOk())
                .andExpect(jsonPath("$.separation.status").value("SUBMITTED"));
        assertThat(ADMIN.queryForObject("""
                select request_summary ->> 'protectedEmployee' from core.workflow_instance where subject_id = ?""",
                String.class, id)).isEqualTo("true");
        decide(w.approver(), id, "APPROVE");
        assertThat(row(id)).containsEntry("status", "SCHEDULED").containsEntry("is_protected", true);
        assertThat((String) events(w.tenant(), "people.TerminationScheduled").getFirst().get("payload"))
                .contains("\"protectedEmployee\":true").contains("\"formerEmployeeEmailOnFile\":false")
                .doesNotContain("PREGNANCY");
    }

    @Test
    void servicePeriodsIncludeEarlierEmploymentsAndHrPeriodsButNoAmounts() throws Exception {
        World w = world("sep-periods");
        // an earlier employment of the same person in the same legal entity, with severance already paid
        UUID earlier = Ids.newId();
        ADMIN.update("""
                insert into hr.employment (tenant_id, id, person_id, legal_entity_id, employee_no, hire_date, end_date,
                       status, worker_category_code)
                values (?, ?, ?, ?, ?, ?, ?, 'TERMINATED', 'EMPLOYEE')""", w.tenant(), earlier, w.emp().personId(),
                w.le(), "OLD" + earlier.toString().substring(30), Date.valueOf("2018-01-01"), Date.valueOf("2021-06-30"));
        UUID id = separationId(create(w.hr(), w.emp().employmentId(),
                attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-01", null)));
        var added = postJson(w.hr(), "/api/v1/people/separations/" + id + "/service-periods",
                "{\"periodKind\":\"PAID_SEVERANCE\",\"periodFrom\":\"2018-01-01\",\"periodTo\":\"2021-06-30\","
                        + "\"sourceEmploymentId\":\"" + earlier + "\",\"note\":\"paid in 2021\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.derived").value(false));
        postJson(w.hr(), "/api/v1/people/separations/" + id + "/service-periods",
                "{\"periodKind\":\"BONUS\",\"periodFrom\":\"2018-01-01\",\"periodTo\":\"2021-06-30\"}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-period-kind"));
        mvc.perform(get("/api/v1/people/separations/" + id).with(as(w.hr())))
                .andExpect(jsonPath("$.servicePeriods", hasSize(3)))
                .andExpect(jsonPath("$.servicePeriods[?(@.sourceEmploymentId=='" + earlier + "')].periodKind",
                        hasItem("WORKED")))
                .andExpect(jsonPath("$.servicePeriods[?(@.periodKind=='PAID_SEVERANCE')].derived").value(false));
        UUID derivedId = UUID.fromString(ADMIN.queryForObject("""
                select id::text from hr.separation_service_period where separation_id = ? and note like 'auto:%'
                 limit 1""", String.class, id));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/people/separations/" + id + "/service-periods/" + derivedId).with(as(w.hr())))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("derived-service-period"));
        UUID own = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body(added), "$.id"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/people/separations/" + id + "/service-periods/" + own).with(as(w.hr())))
                .andExpect(status().isNoContent());
    }

    @Test
    void separationsAreScopedAndTenantIsolated() throws Exception {
        World w = world("sep-scope");
        UUID id = separationId(create(w.hr(), w.emp().employmentId(),
                attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-01", null)).andExpect(status().isCreated()));
        UUID otherLe = FIX.legalEntity(w.tenant(), "LE2");
        TestUser outsider = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(otherLe),
                PeoplePermissions.SEPARATION_VIEW, PeoplePermissions.SEPARATION_CREATE, PeoplePermissions.SEPARATION_UPDATE);
        mvc.perform(get("/api/v1/people/separations/" + id).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/people/separations").with(as(outsider))).andExpect(jsonPath("$", hasSize(0)));
        create(outsider, w.emp().employmentId(), attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-01", null))
                .andExpect(status().isNotFound());
        submit(outsider, id, "\"noPersonalEmail\":true").andExpect(status().isNotFound());
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get("/api/v1/people/separations/" + id).with(as(viewer))).andExpect(status().isForbidden());

        World x = world("sep-scope-x");
        mvc.perform(get("/api/v1/people/separations/" + id).with(as(x.hr()))).andExpect(status().isNotFound());
        create(x.hr(), w.emp().employmentId(), attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-01", null))
                .andExpect(status().isNotFound());
    }
}
