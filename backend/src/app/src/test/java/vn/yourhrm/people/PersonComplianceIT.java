package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonJobs;
import vn.yourhrm.people.api.PersonResidencyProposals;
import vn.yourhrm.people.internal.application.person.ProtectedStatusQuery;
import vn.yourhrm.people.internal.application.person.WorkPermitGuard;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Tax residency with HR approval and Payroll proposals (US-PPL-013/014), VN presence, special statuses and the
 * protection query (US-PPL-012, BR-PPL-073 input), work permits with the coverage guard and expiry alerts
 * (US-PPL-011).
 */
class PersonComplianceIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final String PERSONS = "/api/v1/people/persons/";

    @Autowired
    PersonResidencyProposals proposals;
    @Autowired
    ProtectedStatusQuery protection;
    @Autowired
    WorkPermitGuard permitGuard;
    @Autowired
    PersonJobs jobs;
    @Autowired
    BusinessClock clock;

    record World(UUID tenant, UUID le, OrgUnit unit, TestUser hr, TestUser approver, Employee emp) {
    }

    World world(String name) {
        UUID tenant = FIX.tenant(name);
        PersonTestData.codes(ADMIN, tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Smith", "John", LocalDate.of(2025, 1, 6));
        ADMIN.update("update hr.person set nationality_code = 'GB' where id = ?", emp.personId());
        TestUser approver = FIX.user(tenant, "approver-" + name + "@test.local");
        UUID role = FIX.role(tenant, "HR_ADMIN", false, "core.workflow.view", PeoplePermissions.EMPLOYEE_VIEW,
                PeoplePermissions.TAX_RESIDENCY_UPDATE);
        FIX.assign(approver, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        return new World(tenant, le, unit, PPL.hrAdmin(tenant), approver, emp);
    }

    ResultActions postJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(post(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions putJson(TestUser user, String url, String json) throws Exception {
        return mvc.perform(put(url).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", null), call);
    }

    static String read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path).toString();
    }

    UUID pendingTask(TestUser user) throws Exception {
        String json = mvc.perform(get("/api/v1/core/workflow/tasks").with(as(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$[*].taskId");
        assertThat(ids).hasSize(1);
        return UUID.fromString(ids.getFirst());
    }

    // ------------------------------------------------------------------------------------------- tax residency

    @Test
    void taxResidencyChangesAreApprovedByHrAndNeverOverlap() throws Exception {
        World w = world("ppl-taxres");
        String url = PERSONS + w.emp().personId() + "/tax-residencies";
        ResultActions first = postJson(w.hr(), url, """
                {"status": "NON_RESIDENT", "basis": "NOT_MET", "validFrom": "2025-01-06", "arrivalDate": "2025-01-05"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.residency.approvalStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.workflowStatus").value("IN_PROGRESS"));
        UUID firstId = UUID.fromString(read(first, "$.residency.id"));
        // one change at a time
        postJson(w.hr(), url, """
                {"status": "RESIDENT", "basis": "DAYS_183", "validFrom": "2025-07-01"}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("tax-residency-pending"));
        mvc.perform(post("/api/v1/core/workflow/tasks/" + pendingTask(w.approver()) + "/actions").with(as(w.approver()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isNoContent());
        assertThat(ADMIN.queryForObject("select approval_status from hr.tax_residency where id = ?", String.class, firstId))
                .isEqualTo("APPROVED");
        assertThat(ADMIN.queryForObject("select approved_by from hr.tax_residency where id = ?", UUID.class, firstId))
                .isEqualTo(w.approver().userId());

        // a new status from 2025-07-01 closes the approved one in the same transaction (BR-PPL-023)
        UUID second = UUID.fromString(read(postJson(w.hr(), url, """
                {"status": "RESIDENT", "basis": "DAYS_183", "validFrom": "2025-07-01", "taxYear": 2025, "daysInVn": 190}""")
                .andExpect(status().isCreated()), "$.residency.id"));
        mvc.perform(post("/api/v1/core/workflow/tasks/" + pendingTask(w.approver()) + "/actions").with(as(w.approver()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(url).with(as(w.hr())))
                .andExpect(jsonPath("$[0].validTo").value("2025-07-01"))
                .andExpect(jsonPath("$[1].id").value(second.toString()))
                .andExpect(jsonPath("$[1].approvalStatus").value("APPROVED"));
        String payload = ADMIN.queryForObject("""
                select payload::text from hr.outbox_event where tenant_id = ? and person_id = ?
                   and payload->>'reason' = 'TAX_RESIDENCY_APPROVED' and payload->'taxResidency'->>'taxResidencyId' = ?""",
                String.class, w.tenant(), w.emp().personId(), second.toString());
        assertThat(payload.replace(" ", "")).contains("\"status\":\"RESIDENT\"", "\"basis\":\"DAYS_183\"", "\"validFrom\":\"2025-07-01\"");

        // history is closed forward only; basis must fit the status
        postJson(w.hr(), url, """
                {"status": "NON_RESIDENT", "basis": "NOT_MET", "validFrom": "2025-03-01"}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("tax-residency-overlap"));
        postJson(w.hr(), url, """
                {"status": "RESIDENT", "basis": "NOT_MET", "validFrom": "2026-01-01"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-tax-residency-basis"));
        // a rejected change stays rejected
        postJson(w.hr(), url, """
                {"status": "NON_RESIDENT", "basis": "NOT_MET", "validFrom": "2026-01-01"}""").andExpect(status().isCreated());
        mvc.perform(post("/api/v1/core/workflow/tasks/" + pendingTask(w.approver()) + "/actions").with(as(w.approver()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REJECT\",\"comment\":\"no evidence\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(url).with(as(w.hr()))).andExpect(jsonPath("$[2].approvalStatus").value("REJECTED"));
    }

    @Test
    void payrollProposalsAreReviewedByHr() throws Exception {
        World w = world("ppl-proposal");
        UUID p1 = as(w.hr(), () -> proposals.receive(new PersonResidencyProposals.Proposal(w.emp().personId(),
                "PAY-183-2025-001", (short) 2025, (short) 200, "RESIDENT", "DAYS_183", LocalDate.of(2025, 1, 1), null)));
        // idempotent on the proposal reference
        assertThat(as(w.hr(), () -> proposals.receive(new PersonResidencyProposals.Proposal(w.emp().personId(),
                "PAY-183-2025-001", (short) 2025, (short) 200, "RESIDENT", "DAYS_183", LocalDate.of(2025, 1, 1), null))))
                .isEqualTo(p1);
        UUID p2 = as(w.hr(), () -> proposals.receive(new PersonResidencyProposals.Proposal(w.emp().personId(),
                "PAY-183-2026-007", (short) 2026, (short) 100, "NON_RESIDENT", "NOT_MET", LocalDate.of(2026, 1, 1), null)));
        mvc.perform(get(PERSONS + "residency-proposals").with(as(w.hr()))).andExpect(jsonPath("$.length()").value(2));
        TestUser other = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(FIX.legalEntity(w.tenant(), "LE9")),
                PeoplePermissions.TAX_RESIDENCY_UPDATE);
        mvc.perform(get(PERSONS + "residency-proposals").with(as(other))).andExpect(jsonPath("$.length()").value(0));

        postJson(w.hr(), PERSONS + "residency-proposals/" + p1 + "/approve", "{\"version\": 0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.source").value("PAYROLL_PROPOSAL"))
                .andExpect(jsonPath("$.proposalRef").value("PAY-183-2025-001"));
        postJson(w.hr(), PERSONS + "residency-proposals/" + p2 + "/reject", "{\"version\": 0, \"reason\": \"\"}")
                .andExpect(status().isBadRequest());
        postJson(w.hr(), PERSONS + "residency-proposals/" + p2 + "/reject",
                "{\"version\": 0, \"reason\": \"Lease contract shows 183 days\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.approvalStatus").value("REJECTED"));
        assertThat(ADMIN.queryForObject("""
                select change::text from audit.audit_event where tenant_id = ? and object_id = ? and action = 'REJECT'""",
                String.class, w.tenant(), p2)).contains("Lease contract shows 183 days");
        postJson(w.hr(), PERSONS + "residency-proposals/" + p2 + "/approve", "{\"version\": 1}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void presencePeriodsDoNotOverlap() throws Exception {
        World w = world("ppl-presence");
        String url = PERSONS + w.emp().personId() + "/vn-presence-periods";
        postJson(w.hr(), url, "{\"entryDate\": \"2025-01-05\", \"exitDate\": \"2025-03-31\"}").andExpect(status().isCreated());
        UUID open = UUID.fromString(read(postJson(w.hr(), url, "{\"entryDate\": \"2025-05-01\"}")
                .andExpect(status().isCreated()), "$.id"));
        postJson(w.hr(), url, "{\"entryDate\": \"2025-03-31\", \"exitDate\": \"2025-04-10\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("presence-overlap"));
        putJson(w.hr(), url + "/" + open, "{\"version\": 0, \"entryDate\": \"2025-05-01\", \"exitDate\": \"2025-12-20\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.exitDate").value("2025-12-20"));
        assertThat(ADMIN.queryForObject("""
                select payload->'presencePeriod'->>'exitDate' from hr.outbox_event where tenant_id = ?
                   and payload->>'reason' = 'PRESENCE_CHANGED' order by occurred_at desc limit 1""", String.class,
                w.tenant())).isEqualTo("2025-12-20");
    }

    // ------------------------------------------------------------------------------------------- special status

    @Test
    void specialStatusesAreMaskedProtectAndPublish() throws Exception {
        World w = world("ppl-special");
        String url = PERSONS + w.emp().personId() + "/special-statuses";
        UUID pregnancy = UUID.fromString(read(postJson(w.hr(), url, """
                {"statusType": "PREGNANCY", "validFrom": "2026-02-01", "validTo": "2026-10-01",
                 "details": {"certificate": "BV Từ Dũ 2026/123"}}""")
                .andExpect(status().isCreated()), "$.id"));
        postJson(w.hr(), url, """
                {"statusType": "CHILD_UNDER_12M", "validFrom": "2026-10-01"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.validTo").value("2027-10-01"));
        postJson(w.hr(), url, """
                {"statusType": "PREGNANCY", "validFrom": "2026-09-01"}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("special-status-overlap"));
        postJson(w.hr(), url, """
                {"statusType": "MINOR", "validFrom": "2026-01-01"}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("derived-status"));
        // party membership is optional political data: no consent (registry stub) = refused (BR-PPL-151)
        postJson(w.hr(), url, """
                {"statusType": "PARTY_MEMBER", "validFrom": "2020-01-01"}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("consent-required"));
        postJson(w.hr(), url, """
                {"statusType": "HAZARDOUS_JOB", "validFrom": "2026-01-01"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("hazardous-override-incomplete"));
        postJson(w.hr(), url, """
                {"statusType": "HAZARDOUS_JOB", "employmentId": "%s", "validFrom": "2026-01-01",
                 "overrideReason": "Works in the paint shop"}""".formatted(w.emp().employmentId()))
                .andExpect(status().isCreated());

        // the detail is encrypted, the event carries type and period only
        byte[] detail = ADMIN.queryForObject("select detail_enc from hr.special_status where id = ?", byte[].class, pregnancy);
        assertThat(new String(detail, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Từ Dũ");
        String payload = ADMIN.queryForObject("""
                select payload::text from hr.outbox_event where tenant_id = ? and event_type = 'people.SpecialStatusChanged'
                   and payload->>'specialStatusId' = ?""", String.class, w.tenant(), pregnancy.toString());
        assertThat(payload).contains("PREGNANCY", "2026-02-01").doesNotContain("certificate");

        // masked for a viewer without unmasked level (sensitive types read RESTRICTED)
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.SPECIAL_STATUS_VIEW);
        ADMIN.update("""
                update core.role_permission rp set field_mask_level = 'PII' from core.role_assignment ra
                 where ra.user_id = ? and rp.tenant_id = ra.tenant_id and rp.role_id = ra.role_id""", viewer.userId());
        mvc.perform(get(url).with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + pregnancy + "')].statusType").value("RESTRICTED"));

        // BR-PPL-073 input for the separation stream
        assertThat(as(w.hr(), () -> protection.protectedReasons(w.emp().personId(), w.emp().employmentId(),
                LocalDate.of(2026, 5, 1)))).containsExactly("PREGNANCY");
        assertThat(as(w.hr(), () -> protection.protectedReasons(w.emp().personId(), w.emp().employmentId(),
                LocalDate.of(2027, 1, 1)))).containsExactly("CHILD_UNDER_12M");
        assertThat(as(w.hr(), () -> protection.protectedReasons(w.emp().personId(), w.emp().employmentId(),
                LocalDate.of(2028, 1, 1)))).isEmpty();

        postJson(w.hr(), url + "/" + pregnancy + "/cancel", "{\"version\": 0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(as(w.hr(), () -> protection.protectedReasons(w.emp().personId(), w.emp().employmentId(),
                LocalDate.of(2026, 5, 1)))).isEmpty();
    }

    // ------------------------------------------------------------------------------------------- work permits

    @Test
    void workPermitsGuardEndDatesAndAlertBeforeExpiry() throws Exception {
        World w = world("ppl-permit");
        String url = PERSONS + w.emp().personId() + "/work-permits";
        LocalDate today = clock.today();
        LocalDate lastDay = today.plusDays(60);
        postJson(w.hr(), url, """
                {"employmentId": "%s", "permitType": "WORK_PERMIT", "permitNo": "WP-001", "validFrom": "2025-01-01",
                 "status": "ACTIVE"}""".formatted(w.emp().employmentId()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("permit-end-required"));
        UUID permit = UUID.fromString(read(postJson(w.hr(), url, """
                {"employmentId": "%s", "permitType": "WORK_PERMIT", "permitNo": "WP-001", "jobTitleOnPermit": "Engineer",
                 "validFrom": "2025-01-01", "validTo": "%s", "status": "ACTIVE"}"""
                .formatted(w.emp().employmentId(), lastDay.plusDays(1))).andExpect(status().isCreated()), "$.id"));
        postJson(w.hr(), url, """
                {"employmentId": "%s", "permitType": "WORK_PERMIT", "permitNo": "WP-002", "validFrom": "%s",
                 "validTo": "%s", "status": "ACTIVE"}""".formatted(w.emp().employmentId(), lastDay, lastDay.plusYears(1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("work-permit-overlap"));

        // guard used by the assignment / contract streams (BR-PPL-020)
        as(w.hr(), () -> {
            permitGuard.requireCoverage(w.emp().employmentId(), lastDay);
            return null;
        });
        assertThatThrownBy(() -> as(w.hr(), () -> {
            permitGuard.requireCoverage(w.emp().employmentId(), lastDay.plusDays(1));
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining(lastDay.toString());
        assertThatThrownBy(() -> as(w.hr(), () -> {
            permitGuard.requireCoverage(w.emp().employmentId(), null);
            return null;
        })).isInstanceOf(ApiException.class);
        // Vietnamese nationals need no permit
        Employee local = PPL.employee(w.tenant(), w.le(), w.unit(), "Lê", "Minh", LocalDate.of(2025, 1, 6));
        ADMIN.update("update hr.person set nationality_code = 'VN' where id = ?", local.personId());
        as(w.hr(), () -> {
            permitGuard.requireCoverage(local.employmentId(), null);
            return null;
        });

        // alerts at 60 days (first offset), list of expiring permits
        assertThat(as(w.hr(), () -> jobs.workPermitExpiryAlerts(today))).isEqualTo(1);
        assertThat(as(w.hr(), () -> jobs.workPermitExpiryAlerts(today))).isZero();
        assertThat(as(w.hr(), () -> jobs.workPermitExpiryAlerts(today.plusDays(15)))).isEqualTo(1);   // 45 days
        mvc.perform(get(PERSONS + "expiring-work-permits?withinDays=60").with(as(w.hr())))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].daysLeft").value(60))
                .andExpect(jsonPath("$[0].renewalInProgress").value(false));

        // a recorded renewal lifts the block and stops the alerts
        postJson(w.hr(), url, """
                {"employmentId": "%s", "permitType": "WORK_PERMIT", "permitNo": "WP-002", "validFrom": "%s",
                 "validTo": "%s", "renewalOfId": "%s", "status": "APPLIED"}"""
                .formatted(w.emp().employmentId(), lastDay.plusDays(1), lastDay.plusYears(2), permit))
                .andExpect(status().isCreated());
        as(w.hr(), () -> {
            permitGuard.requireCoverage(w.emp().employmentId(), lastDay.plusYears(1));
            return null;
        });
        assertThat(as(w.hr(), () -> jobs.workPermitExpiryAlerts(today.plusDays(15)))).isZero();

        // scope: an HR user of another legal entity cannot add a permit to this employment
        TestUser otherHr = PPL.userWith(w.tenant(), "LEGAL_ENTITY", List.of(FIX.legalEntity(w.tenant(), "LE9")),
                PeoplePermissions.WORK_PERMIT_UPDATE, PeoplePermissions.EMPLOYEE_VIEW);
        postJson(otherHr, url, """
                {"employmentId": "%s", "permitType": "EXEMPTION_CERT", "permitNo": "EX-1", "validFrom": "2025-01-01"}"""
                .formatted(w.emp().employmentId())).andExpect(status().isNotFound());
    }
}
