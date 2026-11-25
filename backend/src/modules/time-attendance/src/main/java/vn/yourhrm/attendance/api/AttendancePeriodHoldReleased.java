package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code att.AttendancePeriodHoldReleased} (ATT-P-18, BR-ATT-079/088): a hold ended without approval (REJECTED,
 * WITHDRAWN, CANCELLED, REOPEN_REQUESTED), or a consumed hold was released by a payroll unlock (UNLOCKED).
 */
public record AttendancePeriodHoldReleased(
        UUID tenantId,
        UUID legalEntityId,
        UUID holdId,
        UUID periodId,
        LocalDate periodStart,
        UUID resultId,
        int resultVersion,
        String scope,
        UUID employmentId,
        UUID payrollRunId,
        String reason) implements AttendanceEvent {

    public static final String TYPE = "att.AttendancePeriodHoldReleased";

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "attendance_period";
    }

    @Override
    public UUID aggregateId() {
        return periodId;
    }

    @Override
    public LocalDate effectiveDate() {
        return periodStart;
    }
}
