package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code att.AttendancePeriodHoldGranted} (ATT-P-18, BR-ATT-079): Payroll holds one result version for a run. Written
 * in the transaction of the hold row.
 */
public record AttendancePeriodHoldGranted(
        UUID tenantId,
        UUID legalEntityId,
        UUID holdId,
        UUID periodId,
        UUID payGroupId,
        LocalDate periodStart,
        UUID resultId,
        int resultVersion,
        String scope,
        UUID employmentId,
        UUID payrollRunId) implements AttendanceEvent {

    public static final String TYPE = "att.AttendancePeriodHoldGranted";

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
