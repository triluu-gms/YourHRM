package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Time &amp; Attendance's scheduled work, the entry points for the platform job scheduler (SaaS) once it exists. Until
 * then T&amp;A's own scheduler runs them ({@code yhrm.attendance.jobs.enabled}, default on outside tests): partitions
 * and the nightly run at 01:30, the reminders ({@link AttendanceReminderJobs}) at 07:00, Vietnam time. Every step is
 * idempotent and re-runnable; a failing step is reported and never stops the others.
 */
public interface AttendanceJobs {

    /** Months of partitions kept ahead of the current month (DESIGN §10.2: 3 periods). */
    int PARTITION_MONTHS_AHEAD = 3;

    /**
     * @param step     e.g. PERIOD_REVIEW, RECALCULATION, LEAVE_ACCRUAL
     * @param count    what the step processed (periods, employments, ledger rows ...)
     * @param failures failures of single items (never stop the step) or the step's own error
     */
    record StepResult(String step, int count, List<String> failures) {
    }

    record NightlySummary(LocalDate day, List<StepResult> steps) {

        public boolean failed() {
            return steps.stream().anyMatch(s -> !s.failures().isEmpty());
        }
    }

    /** Platform step: creates the missing monthly partitions of the partitioned att tables. */
    int maintainPartitions();

    /**
     * The nightly run of the current tenant (bound {@code TenantContext}, system actor) for {@code today}:
     * <ol>
     *   <li>PERIOD_REVIEW: OPEN periods that ended move to IN_REVIEW (gap §7.2 D+1, BR-ATT-073);</li>
     *   <li>RECALCULATION: full reprocessing of every employment from the earliest open period (at most
     *       {@code NIGHTLY_MAX_DAYS} back) to today (BR-ATT-038, NFR-ATT-002); CLOSED days are untouched and LOCKED
     *       days only raise retro requests;</li>
     *   <li>LEAVE_CARRY_EXPIRY daily; LEAVE_ACCRUAL of the previous and the current month and LEAVE_YEAR_END of the
     *       previous leave year on the first days of a month (BR-ATT-058/060);</li>
     *   <li>HOLD_RECONCILIATION: T&amp;A's side of the close-hold reconciliation (BR-ATT-088), incidents logged.</li>
     * </ol>
     */
    NightlySummary runNightly(LocalDate today);
}
