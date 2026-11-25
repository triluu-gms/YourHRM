package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code att.AttendancePeriodLocked} (ATT-P-18, BR-ATT-088): the held version was consumed by an APPROVED payroll
 * run. For scope PERIOD the period is now LOCKED; for scope EMPLOYMENT only that employment's result is.
 */
public record AttendancePeriodLocked(
        UUID tenantId,
        UUID legalEntityId,
        UUID periodId,
        UUID payGroupId,
        LocalDate periodStart,
        UUID resultId,
        int lockedVersion,
        String scope,
        UUID employmentId,
        UUID payrollRunId) implements AttendanceEvent {

    public static final String TYPE = "att.AttendancePeriodLocked";

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
