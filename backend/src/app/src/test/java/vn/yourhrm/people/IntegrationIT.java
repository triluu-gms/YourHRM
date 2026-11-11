package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.api.AssignmentJobs;
import vn.yourhrm.people.api.SeparationEvents.EmployeeTerminated;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Integration fixes of wave 2: F1 (the first AssignmentChanged / SalaryChanged of a hire wait for its approval and are
 * published exactly once), F3 (a completed termination ends the PIT designation, re-derives insurance participation
 * and terminates the live contract), F4 (a signed appendix with pay / assignment terms creates the salary / assignment
 * version linked to it, with the salary validations) and F5 (the expiry list's "terminate at expiry" action).
 */
class IntegrationIT extends EmploymentTestSupport {

    @Autowired
    AssignmentJobs assignmentJobs;
    @Autowired
    HrOutbox outbox;
    @Autowired
    @Qualifier(HrDb.TX)
    PlatformTransactionManager hrTx;

    static int eventCount(UUID employment, String type) {
        return count("select count(*) from hr.outbox_event where employment_id = ? and event_type = ?", employment, type);
    }

    ResultActions signScan(TestUser user, String path, String signDate, int version) throws Exception {
        var req = multipart(path).file(new MockMultipartFile("file", "scan.pdf", "application/pdf", ContractTestBase.PDF))
                .param("version", String.valueOf(version));
        if (signDate != null) {
            req.param("signDate", signDate);
        }
        return mvc.perform(req.with(as(user)));
    }

    /** Signs the hire's DRAFT contract; returns its id. */
    UUID signContract(World w, UUID employment, LocalDate signDate) throws Exception {
        UUID c = ADMIN.queryForObject("select id from hr.contract where employment_id = ?", UUID.class, employment);
        int v = ADMIN.queryForObject("select version from hr.contract where id = ?", Integer.class, c);
        signScan(w.hr(), "/api/v1/people/contracts/" + c + "/sign", signDate.toString(), v).andExpect(status().isOk());
        return c;
    }

    // ---------------------------------------------------------------------------------------------------- F1

    @Test
    void aBackDatedHireIsAnnouncedOnlyAfterItsApprovalAndExactlyOnce() throws Exception {
        World w = employmentWorld("int-f1-past");
        UUID id = hire(w, hireBody(newPerson("001095000801"), w.le(), w.unit().id(), w.location(), TODAY.minusDays(3),
                10_000_000));
        assertThat(eventCount(id, "people.AssignmentChanged")).isZero();
        assertThat(eventCount(id, "people.SalaryChanged")).isZero();
        // the daily jobs do not announce it either while the hire is pending
        as(w.hr(), () -> assignmentJobs.publishDueChanges(TODAY));
        assertThat(eventCount(id, "people.AssignmentChanged")).isZero();

        decide(w.approver(), id, "APPROVE");
        assertThat(employmentStatus(id)).isEqualTo("ACTIVE");
        assertThat(eventCount(id, "people.EmployeeHired")).isEqualTo(1);
        assertThat(eventCount(id, "people.AssignmentChanged")).isEqualTo(1);
        assertThat(eventCount(id, "people.SalaryChanged")).isEqualTo(1);
        as(w.hr(), () -> assignmentJobs.publishDueChanges(TODAY));
        daily(w, TODAY);
        assertThat(eventCount(id, "people.AssignmentChanged")).isEqualTo(1);
        assertThat(eventCount(id, "people.SalaryChanged")).isEqualTo(1);
        // F2: the hire decision went PENDING_APPROVAL -> APPROVED through DecisionService and kept its workflow link
        assertThat(ADMIN.queryForObject("select status || ':' || (workflow_instance_id is not null) from hr.decision "
                + "where employment_id = ?", String.class, id)).isEqualTo("APPROVED:true");
    }

    @Test
    void aFutureHireApprovedEarlyIsAnnouncedOnItsHireDateExactlyOnce() throws Exception {
        World w = employmentWorld("int-f1-future");
        LocalDate hireDate = TODAY.plusDays(4);
        UUID id = hireApproved(w, hireBody(newPerson("001095000802"), w.le(), w.unit().id(), w.location(), hireDate,
                10_000_000));
        assertThat(eventCount(id, "people.AssignmentChanged")).isZero();
        as(w.hr(), () -> assignmentJobs.publishDueChanges(hireDate)); // the due job comes first ...
        assertThat(eventCount(id, "people.AssignmentChanged")).isEqualTo(1);
        assertThat(eventCount(id, "people.SalaryChanged")).isEqualTo(1);
        assertThat(daily(w, hireDate).activated()).isEqualTo(1); // ... then the activation: nothing twice
        assertThat(eventCount(id, "people.AssignmentChanged")).isEqualTo(1);
        assertThat(eventCount(id, "people.SalaryChanged")).isEqualTo(1);
    }

    @Test
    void aRejectedBackDatedHireIsNeverAnnounced() throws Exception {
        World w = employmentWorld("int-f1-reject");
        UUID id = hire(w, hireBody(newPerson("001095000803"), w.le(), w.unit().id(), w.location(), TODAY.minusDays(1),
                10_000_000));
        decide(w.approver(), id, "REJECT");
        as(w.hr(), () -> assignmentJobs.publishDueChanges(TODAY));
        assertThat(eventCount(id, "people.AssignmentChanged")).isZero();
        assertThat(eventCount(id, "people.SalaryChanged")).isZero();
    }

    // ---------------------------------------------------------------------------------------------------- F3

    @Test
    void aCompletedTerminationEndsInsuranceParticipationAndTerminatesTheContract() throws Exception {
        World w = employmentWorld("int-f3");
        LocalDate hireDate = TODAY.minusDays(40);
        UUID id = hireApproved(w, hireBody(newPerson("001095000804"), w.le(), w.unit().id(), w.location(), hireDate,
                10_000_000));
        UUID contract = signContract(w, id, hireDate);
        assertThat(count("select count(*) from hr.insurance_participation where employment_id = ? and status = 'ACTIVE' "
                + "and valid_to is null", id)).isEqualTo(4);
        LocalDate end = TODAY.minusDays(2);
        UUID person = ADMIN.queryForObject("select person_id from hr.employment where id = ?", UUID.class, id);
        as(w.hr(), () -> new TransactionTemplate(hrTx).execute(s -> {
            ADMIN.update("update hr.employment set status = 'TERMINATED', end_date = ?, last_working_date = ? where id = ?",
                    end, end, id);
            return outbox.publish(new EmployeeTerminated(w.tenant(), w.le(), person, id, end, UUID.randomUUID(), null,
                    "RESIGNATION", "EMPLOYEE_RESIGNATION", null, end, end, 0, null, null, List.of(), end, true, false));
        }));
        assertThat(ADMIN.queryForObject("select status || ':' || end_date from hr.contract where id = ?", String.class,
                contract)).isEqualTo("TERMINATED:" + end);
        assertThat(count("select count(*) from hr.insurance_participation where employment_id = ? and status = 'ACTIVE' "
                + "and (valid_to is null or valid_to > ?)", id, end.plusDays(1))).isZero();
    }

    // ---------------------------------------------------------------------------------------------------- F4

    @Test
    void aSignedAppendixWithPayAndAssignmentTermsCreatesTheVersionsLinkedToIt() throws Exception {
        World w = employmentWorld("int-f4");
        LocalDate hireDate = TODAY.minusDays(30);
        UUID id = hireApproved(w, hireBody(newPerson("001095000805"), w.le(), w.unit().id(), w.location(), hireDate,
                10_000_000));
        UUID contract = signContract(w, id, hireDate);
        String path = "/api/v1/people/contracts/" + contract + "/appendices";
        LocalDate eff = TODAY.minusDays(5);

        // below the regional minimum wage: the signature fails, nothing is applied or announced
        String low = body(postJson(w.hr(), path, "{\"effectiveDate\":\"" + eff + "\",\"changes\":{\"salary\":{\"base\":1000}}}")
                .andExpect(status().isCreated()));
        UUID lowId = UUID.fromString(JsonPath.read(low, "$.id"));
        signScan(w.hr(), "/api/v1/people/contract-appendices/" + lowId + "/sign", null, 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("below-minimum-wage"));
        assertThat(ADMIN.queryForObject("select status from hr.contract_appendix where id = ?", String.class, lowId))
                .isEqualTo("DRAFT");
        assertThat(count("select count(*) from hr.outbox_event where employment_id = ? and event_type = "
                + "'people.ContractChanged' and payload ->> 'subtype' = 'APPENDIX'", id)).isZero();

        String ok = body(postJson(w.hr(), path, """
                {"effectiveDate":"%s","changes":{"salary":{"base":15000000,"siSalary":15000000},
                 "orgUnitId":"%s","workLocation":"Hà Nội"}}""".formatted(eff, w.unit2().id()))
                .andExpect(status().isCreated()));
        UUID okId = UUID.fromString(JsonPath.read(ok, "$.id"));
        signScan(w.hr(), "/api/v1/people/contract-appendices/" + okId + "/sign", null, 0).andExpect(status().isOk());

        assertThat(ADMIN.queryForObject("""
                select base_salary_amount::bigint || ':' || valid_from || ':' || (decision_id is null)
                  from hr.salary_record where employment_id = ? and contract_appendix_id = ? and superseded_at is null""",
                String.class, id, okId)).isEqualTo("15000000:" + eff + ":true");
        assertThat(ADMIN.queryForObject("""
                select org_unit_id from hr.assignment where employment_id = ? and superseded_at is null
                   and valid_from = ? and assignment_type = 'PRIMARY'""", UUID.class, id, eff)).isEqualTo(w.unit2().id());
        String salaryEvent = ADMIN.queryForObject("""
                select payload::text from hr.outbox_event where employment_id = ? and event_type = 'people.SalaryChanged'
                 order by created_at desc limit 1""", String.class, id);
        assertThat((String) JsonPath.read(salaryEvent, "$.contractAppendixId")).isEqualTo(okId.toString());
        assertThat((String) JsonPath.read(salaryEvent, "$.changeKind")).isEqualTo("CHANGE");
        String assignmentEvent = ADMIN.queryForObject("""
                select payload::text from hr.outbox_event where employment_id = ? and event_type = 'people.AssignmentChanged'
                 order by created_at desc limit 1""", String.class, id);
        assertThat((String) JsonPath.read(assignmentEvent, "$.contractAppendixId")).isEqualTo(okId.toString());
    }

    // ---------------------------------------------------------------------------------------------------- F5

    @Test
    void theExpiryListActionOpensTheSeparationThroughTheSeparationStream() throws Exception {
        World w = employmentWorld("int-f5");
        LocalDate hireDate = TODAY.minusDays(20);
        UUID id = hireApproved(w, hireBody(newPerson("001095000806"), w.le(), w.unit().id(), w.location(), hireDate,
                10_000_000));
        UUID contract = signContract(w, id, hireDate);
        // an indefinite contract has no expiry: the separation stream refuses the CONTRACT_EXPIRY ground
        postJson(w.hr(), "/api/v1/people/contracts/" + contract + "/terminate-at-expiry", "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("contract-without-end-date"));
        World other = employmentWorld("int-f5-x");
        postJson(other.hr(), "/api/v1/people/contracts/" + contract + "/terminate-at-expiry", "{}")
                .andExpect(status().isNotFound());
    }
}
