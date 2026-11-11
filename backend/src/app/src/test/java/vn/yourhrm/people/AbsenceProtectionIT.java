package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.people.api.IntegrationInbox;
import vn.yourhrm.people.api.IntegrationInbox.AbsenceChange;
import vn.yourhrm.people.api.IntegrationInbox.Outcome;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Leave-based protection against employer-initiated termination (BR-PPL-073, US-PPL-063): {@code att.AbsenceChanged}
 * feeds {@code hr.ext_att_absence}; People's default {@code SeparationAbsences} reads it, so a protective absence on
 * the termination date blocks like a special status, and its cancellation lifts the block.
 */
class AbsenceProtectionIT extends SeparationTestBase {

    @Autowired
    IntegrationInbox inbox;

    @Test
    void aProtectiveAbsenceOnTheTerminationDateBlocksEmployerTerminationUntilItIsCancelled() throws Exception {
        World w = world("absence-protect");
        UUID emp = w.emp().employmentId();
        UUID absence = UUID.randomUUID();
        AbsenceChange sick = new AbsenceChange(absence, emp, "SICK_TREATMENT", LocalDate.parse("2026-02-10"),
                LocalDate.parse("2026-03-10"), true, "APPROVED");
        UUID eventId = UUID.randomUUID();
        assertThat(in(w.hr(), () -> inbox.absenceChanged(eventId, sick))).isEqualTo(Outcome.PROCESSED);
        assertThat(in(w.hr(), () -> inbox.absenceChanged(eventId, sick))).isEqualTo(Outcome.DUPLICATE);
        assertThat(ADMIN.queryForMap("select status, is_protective from hr.ext_att_absence where att_absence_id = ?",
                absence)).containsEntry("status", "ACTIVE").containsEntry("is_protective", true);

        create(w.hr(), emp, attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-20", null))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("protected-employee"))
                .andExpect(jsonPath("$.reasons[0]").value("SICK_TREATMENT"));
        // a user without the special-status view does not learn the (P3) leave type
        TestUser limited = PPL.userWith(w.tenant(), "TENANT", List.of(), PeoplePermissions.SEPARATION_CREATE);
        create(limited, emp, attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-20", null))
                .andExpect(status().is(422)).andExpect(jsonPath("$.reasons[0]").value("PROTECTED_LEAVE"));
        // the employee's own resignation is only flagged; leave data is now available
        postJson(w.hr(), "/api/v1/people/employments/" + emp + "/separation/preview",
                attrs("EMPLOYEE_RESIGNATION", "2026-01-02", "2026-02-20", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.protection.protectedEmployee").value(true))
                .andExpect(jsonPath("$.protection.leaveDataAvailable").value(true))
                .andExpect(jsonPath("$.protection.exceptionRequired").value(false));
        // a termination before the absence is not affected
        postJson(w.hr(), "/api/v1/people/employments/" + emp + "/separation/preview",
                attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-05", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.protection.protectedEmployee").value(false));

        // T&A cancels the absence: the block is lifted
        assertThat(in(w.hr(), () -> inbox.absenceChanged(UUID.randomUUID(), new AbsenceChange(absence, emp, null,
                null, null, false, "CANCELLED")))).isEqualTo(Outcome.PROCESSED);
        assertThat(ADMIN.queryForObject("select status from hr.ext_att_absence where att_absence_id = ?", String.class,
                absence)).isEqualTo("CANCELLED");
        create(w.hr(), emp, attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-20", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.protection.protectedEmployee").value(false))
                .andExpect(jsonPath("$.protection.leaveDataAvailable").value(true));
        // the P3 leave type is never in People's events or inbox errors
        assertThat(ADMIN.queryForObject("select count(*) from hr.outbox_event where tenant_id = ? "
                + "and payload::text like '%SICK_TREATMENT%'", Integer.class, w.tenant())).isZero();
    }

    @Test
    void aNonProtectiveAbsenceDoesNotProtectAndAnUnknownCancellationIsSkipped() throws Exception {
        World w = world("absence-plain");
        UUID emp = w.emp().employmentId();
        assertThat(in(w.hr(), () -> inbox.absenceChanged(UUID.randomUUID(), new AbsenceChange(UUID.randomUUID(), emp,
                "ANNUAL", LocalDate.parse("2026-02-16"), LocalDate.parse("2026-02-27"), false, "APPROVED"))))
                .isEqualTo(Outcome.PROCESSED);
        postJson(w.hr(), "/api/v1/people/employments/" + emp + "/separation/preview",
                attrs("EMPLOYER_UNILATERAL", "2026-01-02", "2026-02-20", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.protection.protectedEmployee").value(false))
                .andExpect(jsonPath("$.protection.leaveDataAvailable").value(true));
        assertThat(in(w.hr(), () -> inbox.absenceChanged(UUID.randomUUID(), new AbsenceChange(UUID.randomUUID(), emp,
                null, null, null, false, "CANCELLED")))).isEqualTo(Outcome.SKIPPED);
        // an invalid event is dead-lettered without the leave type in its error
        UUID bad = UUID.randomUUID();
        assertThat(in(w.hr(), () -> inbox.absenceChanged(bad, new AbsenceChange(UUID.randomUUID(), emp,
                "MATERNITY", LocalDate.parse("2026-03-01"), LocalDate.parse("2026-02-01"), true, "APPROVED"))))
                .isEqualTo(Outcome.FAILED);
        assertThat(ADMIN.queryForObject("select last_error from hr.inbox_message where event_id = ?", String.class,
                bad)).doesNotContain("MATERNITY");
    }
}
