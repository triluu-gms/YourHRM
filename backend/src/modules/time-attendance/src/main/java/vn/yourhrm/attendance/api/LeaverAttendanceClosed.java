package vn.yourhrm.attendance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code att.LeaverAttendanceClosed} (ATT-P-03, BR-ATT-075/067): the attendance of a leaver was closed early, up to the
 * last working day, as an EMPLOYMENT-scope result of the period. The leave settlement ({@link LeaveSettlements}) is in
 * the result as LEAVE_ENCASH_DAYS / LEAVE_RECOVER_DAYS lines; the totals are repeated here (days, never money).
 */
public record LeaverAttendanceClosed(
        UUID tenantId,
        UUID legalEntityId,
        UUID employmentId,
        UUID periodId,
        LocalDate dateFrom,
        LocalDate lastWorkingDate,
        UUID resultId,
        int resultVersion,
        BigDecimal encashDays,
        BigDecimal recoverDays) implements AttendanceEvent {

    public static final String TYPE = "att.LeaverAttendanceClosed";

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "employment";
    }

    @Override
    public UUID aggregateId() {
        return employmentId;
    }

    @Override
    public LocalDate effectiveDate() {
        return lastWorkingDate;
    }
}
