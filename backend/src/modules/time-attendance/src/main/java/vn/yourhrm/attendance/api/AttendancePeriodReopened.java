package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code att.AttendancePeriodReopened} (ATT-P-04, BR-ATT-073): a CLOSED period went back to OPEN; its current version
 * is withdrawn and a later close publishes a new one. Never published while Payroll holds the period.
 *
 * @param automatic true when the reopen had been requested while the period was held and ran when the hold was
 *                  released (BR-ATT-087)
 */
public record AttendancePeriodReopened(
        UUID tenantId,
        UUID legalEntityId,
        UUID periodId,
        UUID payGroupId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int withdrawnVersion,
        String reason,
        boolean automatic) implements AttendanceEvent {

    public static final String TYPE = "att.AttendancePeriodReopened";

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
