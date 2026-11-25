package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code att.AttendancePeriodClosed} (ATT-P-01, BR-ATT-076): a version of an Attendance Result is published and is
 * now the current one. Payroll reads the lines through {@link AttendanceResults} and may hold that exact version
 * ({@link AttendanceResults#acquireHold}). Also published for a leaver's early close (scope EMPLOYMENT) and on
 * payroll unlock, where the same version becomes current again.
 *
 * @param scope        PERIOD (legal entity x pay group) or EMPLOYMENT (early close of one leaver, BR-ATT-075)
 * @param employmentId EMPLOYMENT scope only
 * @param cause        CLOSE, RECLOSE, EARLY_CLOSE, UNLOCK, or IMPORT (a result imported from Excel, BR-ATT-025)
 * @param retroLines   number of retro delta lines in this version (BR-ATT-071)
 */
public record AttendancePeriodClosed(
        UUID tenantId,
        UUID legalEntityId,
        UUID periodId,
        UUID payGroupId,
        LocalDate periodStart,
        LocalDate periodEnd,
        UUID resultId,
        int resultVersion,
        String scope,
        UUID employmentId,
        String cause,
        String checksum,
        int lineCount,
        int retroLines) implements AttendanceEvent {

    public static final String TYPE = "att.AttendancePeriodClosed";

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
