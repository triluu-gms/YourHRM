package vn.yourhrm.attendance.internal.application.timesheet;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily processing found that the inputs of a day in a LOCKED period changed since the day was computed (BR-ATT-071):
 * the day itself is never rewritten; implementations record the employment and period for a retro delta at the next
 * close. Called inside daily processing's T&amp;A transaction; must be idempotent and never call back into it.
 */
public interface LockedDayChangeSink {

    void lockedDayChanged(UUID employmentId, UUID periodId, LocalDate workDate);
}
