package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.people.api.IntegrationInbox;
import vn.yourhrm.people.api.IntegrationInbox.Outcome;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonResidencyProposals.Proposal;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * People's inbox (US-PPL-082): idempotent on (consumer, event id), dead letters kept as FAILED with attempts and
 * listed for operations, routing to the separation / residency streams and the onboarding readiness event.
 */
class InboxIT extends EmploymentTestSupport {

    @Autowired
    IntegrationInbox inbox;

    @Test
    void aPayGroupAssignmentIsAppliedOnceAndFeedsTheOnboardingReadiness() throws Exception {
        World w = employmentWorld("inbox-paygroup");
        UUID id = hireApproved(w, hireBody(newPerson("001095000601"), w.le(), w.unit().id(), w.location(),
                TODAY.plusDays(10), 10_000_000));
        List<String> before = events(w.tenant(), "people.OnboardingReadinessChanged");
        assertThat(before).isNotEmpty(); // evaluated on EmployeeHired (preview)
        assertThat((String) JsonPath.read(before.getLast(), "$.status")).isEqualTo("BLOCKED");
        assertThat(JsonPath.<List<String>>read(before.getLast(), "$.items[?(@.code == 'PAY_GROUP')].state"))
                .containsExactly("UNKNOWN");

        UUID eventId = UUID.randomUUID();
        assertThat(as(w.hr(), () -> inbox.payGroupAssigned(eventId, id, "PG-MONTHLY", true)))
                .isEqualTo(Outcome.PROCESSED);
        assertThat(as(w.hr(), () -> inbox.payGroupAssigned(eventId, id, "PG-MONTHLY", true)))
                .isEqualTo(Outcome.DUPLICATE);
        List<String> after = events(w.tenant(), "people.OnboardingReadinessChanged");
        assertThat(after).hasSize(before.size() + 1);
        assertThat(JsonPath.<List<String>>read(after.getLast(), "$.items[?(@.code == 'PAY_GROUP')].state"))
                .containsExactly("READY");
        assertThat((String) JsonPath.read(after.getLast(), "$.trigger")).isEqualTo("PAY_GROUP_ASSIGNED");
        // re-evaluating without a change publishes nothing; the pay-group state is kept from the last event
        assertThat(as(w.hr(), () -> inbox.workScheduleAssigned(UUID.randomUUID(), id, true)))
                .isEqualTo(Outcome.PROCESSED);
        List<String> third = events(w.tenant(), "people.OnboardingReadinessChanged");
        assertThat(JsonPath.<List<String>>read(third.getLast(), "$.items[?(@.code == 'PAY_GROUP')].state"))
                .containsExactly("READY");
        assertThat(JsonPath.<List<String>>read(third.getLast(), "$.items[?(@.code == 'WORK_SCHEDULE')].state"))
                .containsExactly("READY");
        assertThat(count("select count(*) from hr.inbox_message where tenant_id = ? and event_id = ?", w.tenant(),
                eventId)).isEqualTo(1);
        // the last state is kept in hr.onboarding_readiness, not read back from the outbox (7-day retention)
        assertThat(count("""
                select count(*) from hr.onboarding_readiness r cross join jsonb_array_elements(r.items) i
                 where r.employment_id = ? and i ->> 'item' = 'PAY_GROUP'
                   and i ->> 'state' = 'READY'""", id)).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select trigger_code from hr.onboarding_readiness where employment_id = ?",
                String.class, id)).isEqualTo("WORK_SCHEDULE_ASSIGNED");
        ADMIN.update("delete from hr.outbox_event where tenant_id = ? and event_type = 'people.OnboardingReadinessChanged'",
                w.tenant());
        assertThat(as(w.hr(), () -> inbox.workScheduleAssigned(UUID.randomUUID(), id, true)))
                .isEqualTo(Outcome.PROCESSED);
        assertThat(events(w.tenant(), "people.OnboardingReadinessChanged")).isEmpty(); // unchanged: nothing published
        assertThat(as(w.hr(), () -> inbox.payGroupAssigned(UUID.randomUUID(), id, "PG-MONTHLY", false)))
                .isEqualTo(Outcome.PROCESSED);
        List<String> changedAgain = events(w.tenant(), "people.OnboardingReadinessChanged");
        assertThat(changedAgain).hasSize(1);
        assertThat(JsonPath.<List<String>>read(changedAgain.getFirst(), "$.items[?(@.code == 'WORK_SCHEDULE')].state"))
                .containsExactly("READY");
    }

    @Test
    void payrollPeriodLocksAndAbsencesAreProjectedOnceAndUnknownEmploymentsDeadLettered() throws Exception {
        World w = employmentWorld("inbox-projections");
        UUID le = ADMIN.queryForObject("select legal_entity_id from hr.employment where id = ?", UUID.class,
                w.emp().employmentId());
        LocalDate end = LocalDate.of(2026, 9, 30);
        UUID lock = UUID.randomUUID();
        assertThat(as(w.hr(), () -> inbox.payPeriodLockChanged(lock, new IntegrationInbox.PayPeriodLockChange(le,
                "2026-09", LocalDate.of(2026, 9, 1), end, true)))).isEqualTo(Outcome.PROCESSED);
        // an older period locked later does not move the lock back
        assertThat(as(w.hr(), () -> inbox.payPeriodLockChanged(UUID.randomUUID(),
                new IntegrationInbox.PayPeriodLockChange(le, "2026-08", LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31), true)))).isEqualTo(Outcome.SKIPPED);
        assertThat(ADMIN.queryForMap("select locked_through, period_code, source_event_id from hr.ext_pay_period_lock "
                + "where tenant_id = ?", w.tenant())).containsEntry("period_code", "2026-09")
                .containsEntry("source_event_id", lock);

        UUID absence = UUID.randomUUID();
        var change = new IntegrationInbox.AbsenceChange(absence, w.emp().employmentId(), "MATERNITY",
                LocalDate.of(2026, 10, 1), null, true, "APPROVED");
        assertThat(as(w.hr(), () -> inbox.absenceChanged(UUID.randomUUID(), change))).isEqualTo(Outcome.PROCESSED);
        // T&A extends the absence: same row updated
        assertThat(as(w.hr(), () -> inbox.absenceChanged(UUID.randomUUID(), new IntegrationInbox.AbsenceChange(
                absence, w.emp().employmentId(), "MATERNITY", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 3, 31),
                true, "APPROVED")))).isEqualTo(Outcome.PROCESSED);
        assertThat(count("select count(*) from hr.ext_att_absence where tenant_id = ? and att_absence_id = ? "
                + "and end_date = date '2027-03-31' and status = 'ACTIVE'", w.tenant(), absence)).isEqualTo(1);
        UUID unknown = UUID.randomUUID();
        assertThat(as(w.hr(), () -> inbox.absenceChanged(unknown, new IntegrationInbox.AbsenceChange(UUID.randomUUID(),
                UUID.randomUUID(), "MATERNITY", LocalDate.of(2026, 10, 1), null, true, "APPROVED"))))
                .isEqualTo(Outcome.FAILED);
        // no P3 leave type in the dead letter
        assertThat(count("select count(*) from hr.inbox_message where event_id = ? and last_error like '%MATERNITY%'",
                unknown)).isZero();
    }

    @Test
    void aFailingEventIsDeadLetteredListedForOperationsAndRetriedOnRedelivery() throws Exception {
        World w = employmentWorld("inbox-dead");
        UUID eventId = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        assertThat(as(w.hr(), () -> inbox.payGroupAssigned(eventId, unknown, "PG", true))).isEqualTo(Outcome.FAILED);
        assertThat(as(w.hr(), () -> inbox.payGroupAssigned(eventId, unknown, "PG", true))).isEqualTo(Outcome.FAILED);
        mvc.perform(get("/api/v1/people/integration/inbox").param("status", "FAILED").with(as(w.hr())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$[0].consumer").value("hr.inbox.pay.PayGroupAssigned"))
                .andExpect(jsonPath("$[0].attempts").value(2))
                .andExpect(jsonPath("$[0].lastError").exists());
        TestUser viewer = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.EMPLOYEE_VIEW);
        mvc.perform(get("/api/v1/people/integration/inbox").with(as(viewer))).andExpect(status().isForbidden());
        World other = employmentWorld("inbox-dead-x");
        mvc.perform(get("/api/v1/people/integration/inbox").param("status", "FAILED").with(as(other.hr())))
                .andExpect(jsonPath("$.length()").value(0));
        // Core's in-process user status events carry no employment link: recorded, nothing to do
        assertThat(as(w.hr(), () -> inbox.userStatusChanged(UUID.randomUUID(), w.hr().userId(), null, "ACTIVE")))
                .isEqualTo(Outcome.SKIPPED);
    }

    @Test
    void payrollEventsAreRoutedToTheSeparationAndResidencyStreams() throws Exception {
        World w = employmentWorld("inbox-route");
        // no separation for the employment: the settlement status has nothing to attach to
        assertThat(as(w.hr(), () -> inbox.finalSettlementStatusChanged(UUID.randomUUID(), w.emp().employmentId(),
                UUID.randomUUID(), "CALCULATED", TODAY.plusDays(14)))).isEqualTo(Outcome.SKIPPED);
        UUID eventId = UUID.randomUUID();
        Proposal p = new Proposal(w.emp().personId(), "PAY-183-" + eventId, (short) TODAY.getYear(), (short) 190,
                "RESIDENT", "DAYS_183", LocalDate.of(TODAY.getYear(), 1, 1), null);
        assertThat(as(w.hr(), () -> inbox.residencyProposed(eventId, p))).isEqualTo(Outcome.PROCESSED);
        assertThat(as(w.hr(), () -> inbox.residencyProposed(eventId, p))).isEqualTo(Outcome.DUPLICATE);
        assertThat(count("select count(*) from hr.tax_residency where person_id = ? and source = 'PAYROLL_PROPOSAL' "
                + "and approval_status = 'PENDING_APPROVAL'", w.emp().personId())).isEqualTo(1);
    }
}
