package vn.yourhrm.attendance.api;

import java.time.LocalDate;

/**
 * Daily reminders of Time &amp; Attendance (BR-ATT-082), run by the platform job scheduler once per tenant inside that
 * tenant's {@code TenantContext}, in the morning. Idempotent and re-runnable on the same day (Core de-duplicates per
 * recipient and key). Until the scheduler exists, {@code POST /api/v1/attendance/reminders/run} runs it.
 */
public interface AttendanceReminderJobs {

    /**
     * @param missingPunch employees reminded of a missing check-in / check-out on the previous day
     * @param carryExpiry  employees reminded of carried leave days expiring within the legal entity's notice
     * @param closeDue     periods whose administrators were reminded of the close deadline
     * @param failures     notifications that could not be sent (logged; never stop the run)
     */
    record Summary(LocalDate day, int missingPunch, int carryExpiry, int closeDue, int failures) {
    }

    /**
     * For {@code today}: missing-punch reminders for the days of {@code today - 1}, carried-leave expiry reminders
     * and close-deadline reminders, each switched per legal entity by T&amp;A's reminder settings.
     */
    Summary runDaily(LocalDate today);
}
