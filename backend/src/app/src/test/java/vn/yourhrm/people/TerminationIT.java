package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.api.ContractEvents.ProbationCompleted;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.SeparationJobs;
import vn.yourhrm.people.api.SeparationSettlements;
import vn.yourhrm.people.api.SeparationSettlements.FinalSettlementStatus;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Termination (BR-PPL-072/075; US-PPL-065/066, IN-19): the daily job terminates on the day after the termination date
 * with {@code people.EmployeeTerminated}; Payroll's settlement status is stored for display and alerts HR; cancelling
 * after termination reinstates; ESS resignation through manager and HR with withdrawal before / after approval; the
 * probation-failure and contract-expiry hooks.
 */
class TerminationIT extends SeparationTestBase {

    @Autowired
    SeparationJobs jobs;

    @Autowired
    SeparationSettlements settlements;

    @Autowired
    HrOutbox outbox;

    @Autowired
    @Qualifier(HrDb.TX)
    PlatformTransactionManager hrTx;

    @Test
    void theDailyJobTerminatesAfterTheTerminationDateAndPayrollsResultIsOnlyDisplayed() throws Exception {
        World w = world("term-job");
        UUID emp = w.emp().employmentId();
        UUID id = scheduled(w, attrs("MUTUAL_AGREEMENT", "2026-01-02", "2026-02-01", null));

        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 2, 1))).employmentsTerminated()).isZero();
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 2, 2))).employmentsTerminated()).isEqualTo(1);
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 2, 2))).employmentsTerminated()).isZero();
        Map<String, Object> e = ADMIN.queryForMap("select status, end_date, last_working_date from hr.employment where id = ?",
                emp);
        assertThat(e).containsEntry("status", "TERMINATED").containsEntry("end_date", Date.valueOf("2026-02-01"))
                .containsEntry("last_working_date", Date.valueOf("2026-02-01"));
        assertThat(row(id)).containsEntry("status", "COMPLETED");
        List<Map<String, Object>> terminated = events(w.tenant(), "people.EmployeeTerminated");
        assertThat(terminated).hasSize(1);
        assertThat(terminated.getFirst()).containsEntry("is_retro", false)
                .containsEntry("effective_date", Date.valueOf("2026-02-01"));
        assertThat((String) terminated.getFirst().get("payload")).contains("\"statutoryCode\":\"TV-TT\"")
                .contains("\"severanceEligibility\":\"ELIGIBLE\"").contains("\"severanceType\":\"SEVERANCE_ALLOWANCE\"")
                .contains("\"periodKind\":\"WORKED\"").contains("\"periodFrom\":\"2024-01-01\"")
                .contains("\"legalGroundCode\":\"MUTUAL_AGREEMENT\"");
        mvc.perform(get("/api/v1/people/separations/" + id).with(as(w.hr())))
                .andExpect(jsonPath("$.employmentPhase").value("TERMINATED"));

        // IN-19: Payroll's result reference and settlement clock, display only, idempotent
        UUID eventId = Ids.newId();
        UUID ref = Ids.newId();
        FinalSettlementStatus st = new FinalSettlementStatus(eventId, emp, ref, "CALCULATED", LocalDate.of(2026, 2, 20));
        assertThat(in(w.hr(), () -> settlements.finalSettlementStatusChanged(st))).isTrue();
        assertThat(in(w.hr(), () -> settlements.finalSettlementStatusChanged(st))).isFalse();
        assertThat(row(id)).containsEntry("payroll_result_ref", ref)
                .containsEntry("settlement_due_date", Date.valueOf("2026-02-20"));
        // alert offsets [3, 1] (default setting)
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 2, 16))).settlementAlerts()).isZero();
        assertThat(in(w.hr(), () -> jobs.runDaily(LocalDate.of(2026, 2, 17))).settlementAlerts()).isEqualTo(1);

        // HR cancels after the termination: decision cancelled, employment reinstated
        postJson(w.hr(), "/api/v1/people/separations/" + id + "/cancel",
                "{\"version\":" + version(id) + ",\"reason\":\"agreement revoked\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(ADMIN.queryForMap("select status, end_date from hr.employment where id = ?", emp))
                .containsEntry("status", "ACTIVE").containsEntry("end_date", null);
        assertThat(ADMIN.queryForObject("select d.status from hr.decision d join hr.separation s on s.decision_id = d.id "
                + "where s.id = ?", String.class, id)).isEqualTo("CANCELLED");
        assertThat((String) events(w.tenant(), "people.TerminationCancelled").getFirst().get("payload"))
                .contains("\"employmentReinstated\":true").contains("\"previousStatus\":\"COMPLETED\"");
    }

    @Test
    void anEssResignationGoesThroughManagerAndHrAndIsWithdrawnWithConsent() throws Exception {
        World w = world("term-ess");
        TestUser employee = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.ESS_VIEW,
                PeoplePermissions.ESS_REQUEST);
        PPL.link(employee, w.emp());
        TestUser manager = FIX.user(w.tenant(), "mgr-" + Ids.newId().toString().substring(28) + "@test.local");
        ADMIN.update("update core.org_unit_version set manager_user_id = ? where org_unit_id = ?", manager.userId(),
                w.unit().id());
        LocalDate lwd = LocalDate.now().plusDays(60);
        String resign = "/api/v1/people/me/resignation";

        mvc.perform(get(resign).with(as(employee))).andExpect(status().isNoContent());
        postJson(employee, resign, "{\"proposedLastWorkingDate\":\"" + lwd + "\"}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("personal-email-required"));
        postJson(employee, resign, "{\"proposedLastWorkingDate\":\"" + LocalDate.now().minusDays(1)
                + "\",\"personalEmail\":\"an@example.vn\"}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("invalid-last-working-date"));
        String body = body(postJson(employee, resign, "{\"proposedLastWorkingDate\":\"" + lwd
                + "\",\"note\":\"Chuyển về quê\",\"personalEmail\":\"an@example.vn\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.separation.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.separation.legalGroundCode").value("EMPLOYEE_RESIGNATION"))
                .andExpect(jsonPath("$.notice.requiredDays").value(45))
                .andExpect(jsonPath("$.notice.shortfallDays").value(0)));
        UUID id = UUID.fromString(JsonPath.read(body, "$.separation.id"));
        assertThat(ADMIN.queryForObject("select request_summary ->> 'note' from core.workflow_instance where subject_id = ?",
                String.class, id)).isEqualTo("Chuyển về quê");
        // v0.6: the employee's note is stored on the separation; an e-mail is on file
        assertThat(ADMIN.queryForMap("select employee_note, no_personal_email from hr.separation where id = ?", id))
                .containsEntry("employee_note", "Chuyển về quê").containsEntry("no_personal_email", false);
        assertThat(JsonPath.<String>read(body, "$.separation.employeeNote")).isEqualTo("Chuyển về quê");
        mvc.perform(get(resign).with(as(employee))).andExpect(jsonPath("$.id").value(id.toString()));
        // ESS does not open HR's separation records
        mvc.perform(get("/api/v1/people/separations/" + id).with(as(employee))).andExpect(status().isForbidden());

        decide(manager, id, "APPROVE");
        assertThat(row(id)).containsEntry("status", "SUBMITTED");
        decide(w.approver(), id, "APPROVE");
        assertThat(row(id)).containsEntry("status", "SCHEDULED");

        // after approval: the withdrawal needs HR's consent
        String wd = "/api/v1/people/me/resignation/" + id + "/withdraw";
        postJson(employee, wd, "{\"note\":\"Gia đình đổi ý\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("WITHDRAWAL_REQUESTED"));
        assertThat(row(id)).containsEntry("status", "SCHEDULED");
        decide(w.approver(), id, "APPROVE");
        assertThat(row(id)).containsEntry("status", "CANCELLED");
        assertThat((String) events(w.tenant(), "people.TerminationCancelled").getFirst().get("payload"))
                .contains("\"employmentReinstated\":false").contains("\"previousStatus\":\"SCHEDULED\"");

        // before approval: withdrawn freely
        UUID second = UUID.fromString(JsonPath.read(body(postJson(employee, resign,
                "{\"proposedLastWorkingDate\":\"" + lwd + "\"}").andExpect(status().isCreated())), "$.separation.id"));
        postJson(employee, "/api/v1/people/me/resignation/" + second + "/withdraw", "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("WITHDRAWN"));
        assertThat(row(second)).containsEntry("status", "WITHDRAWN");
        assertThat(row(second).get("withdrawn_at")).isNotNull();
        // someone else's resignation looks missing
        TestUser stranger = PPL.userWith(w.tenant(), "SELF", List.of(), PeoplePermissions.ESS_REQUEST);
        Employee other = PPL.employee(w.tenant(), w.le(), w.unit(), "Hoàng", "Lan", HIRED);
        PPL.link(stranger, other);
        postJson(stranger, "/api/v1/people/me/resignation/" + id + "/withdraw", "{}").andExpect(status().isNotFound());
    }

    @Test
    void aFailedProbationOpensADraftAndTheExpiryListCanTerminateAtExpiry() throws Exception {
        World w = world("term-hooks");
        Employee trainee = PPL.employee(w.tenant(), w.le(), w.unit(), "Vũ", "Hà", LocalDate.of(2026, 1, 1));
        UUID probation = contract(w.tenant(), trainee.employmentId(), "PROBATION", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 28));
        in(w.hr(), () -> new TransactionTemplate(hrTx).execute(s -> outbox.publish(new ProbationCompleted(w.tenant(),
                w.le(), trainee.personId(), trainee.employmentId(), LocalDate.of(2026, 2, 20), probation, "HD-P", "FAIL",
                LocalDate.of(2026, 2, 20), LocalDate.of(2026, 2, 28), null, null))));
        Map<String, Object> draft = ADMIN.queryForMap("select * from hr.separation where employment_id = ?",
                trainee.employmentId());
        assertThat(draft).containsEntry("status", "DRAFT").containsEntry("legal_ground_code", "PROBATION_FAILED")
                .containsEntry("separation_type", "PROBATION_FAILED")
                .containsEntry("last_working_date", Date.valueOf("2026-02-28"))
                .containsEntry("severance_type", "NONE");
        // a PASS does nothing
        Employee passed = PPL.employee(w.tenant(), w.le(), w.unit(), "Đỗ", "Minh", LocalDate.of(2026, 1, 1));
        in(w.hr(), () -> new TransactionTemplate(hrTx).execute(s -> outbox.publish(new ProbationCompleted(w.tenant(),
                w.le(), passed.personId(), passed.employmentId(), LocalDate.of(2026, 2, 20), Ids.newId(), "HD-Q",
                "PASS", LocalDate.of(2026, 2, 20), LocalDate.of(2026, 2, 28), null, null))));
        assertThat(ADMIN.queryForObject("select count(*) from hr.separation where employment_id = ?", Integer.class,
                passed.employmentId())).isZero();

        Employee fixedTerm = PPL.employee(w.tenant(), w.le(), w.unit(), "Ngô", "Quang", LocalDate.of(2025, 1, 1));
        UUID definite = contract(w.tenant(), fixedTerm.employmentId(), "DEFINITE", LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 12, 31));
        postJson(w.hr(), "/api/v1/people/separations/contract-expiry",
                "{\"contractId\":\"" + definite + "\",\"noticeDate\":\"2026-12-01\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.separation.legalGroundCode").value("CONTRACT_EXPIRY"))
                .andExpect(jsonPath("$.separation.lastWorkingDate").value("2026-12-31"))
                .andExpect(jsonPath("$.separation.initiatedBy").value("AUTOMATIC"))
                .andExpect(jsonPath("$.statutoryCode").value("TV-HHD"));
        UUID indefinite = ADMIN.queryForObject("select id from hr.contract where employment_id = ?", UUID.class,
                w.emp().employmentId());
        postJson(w.hr(), "/api/v1/people/separations/contract-expiry", "{\"contractId\":\"" + indefinite + "\"}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("contract-without-end-date"));
    }
}
