package vn.yourhrm.attendance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.yourhrm.attendance.api.HolidayCalendars.Holiday;

/**
 * Read API of the planned working days of an employment (ATT-P-16 schedule part, US-ATT-007): the schedule pattern
 * assigned to the employment's assignment line (BR-ATT-015), its weekly rest days (BR-ATT-014), the holidays of the
 * resolved calendar and the per-employee substitute days (BR-ATT-003). Always on (R2-18); evaluated in the current
 * {@code TenantContext} without a permission check (in-process module API).
 *
 * <p>When several assignment lines of the employment have a schedule on a day (e.g. a temporary transfer next to the
 * primary line), the line that started last wins.
 */
public interface WorkSchedules {

    /** Maximum span of one {@link #plan} call (inclusive days). */
    int MAX_RANGE_DAYS = 366;

    /**
     * One planned day.
     *
     * @param scheduled         a schedule assignment covers the day (false: no schedule, every other field empty)
     * @param dayKind           WORK, REST (weekly rest day, BR-ATT-014) or OFF (non-working, not a rest day, e.g. the
     *                          free Saturday of an alternating week)
     * @param holiday           the public or company holiday of the day (calendar-level substitute rows are replaced
     *                          by the per-employee derivation), null when none
     * @param substituteForDate the day is a substitute day off for the holiday on that date, which fell on one of the
     *                          employee's rest days (BR-ATT-003)
     */
    record PlannedDay(LocalDate date, boolean scheduled, UUID scheduleAssignmentId, UUID assignmentKey,
            UUID patternId, String patternCode, String dayKind, UUID shiftId, String shiftCode, BigDecimal stdHours,
            BigDecimal dayValue, UUID legalEntityId, UUID locationId, Holiday holiday, LocalDate substituteForDate) {

        /** A scheduled working day that is neither a holiday nor a substitute day off. */
        public boolean isWorkingDay() {
            return scheduled && "WORK".equals(dayKind) && holiday == null && substituteForDate == null;
        }
    }

    /** Planned days of {@code employmentId} in {@code [from, to]}, one per date, ordered. */
    List<PlannedDay> plan(UUID employmentId, LocalDate from, LocalDate to);
}
