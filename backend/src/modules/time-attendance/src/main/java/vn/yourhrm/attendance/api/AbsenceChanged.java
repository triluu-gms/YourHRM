package vn.yourhrm.attendance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code att.AbsenceChanged} (ATT-P-05 / ATT-P-07): a leave request was approved, its approval was withdrawn or an
 * approved leave was shortened (maternity early return, BR-ATT-065). Consumers: Payroll / Insurance (SI absence facts),
 * People (maternity start and return, long unpaid absence), Core (notifications), T&amp;A daily processing (recomputes
 * the days). The payload carries no P3 value (D7): SI claim details are read through the audited T&amp;A endpoint.
 *
 * @param status           APPROVED, CANCELLED (an approved leave withdrawn) or SHORTENED
 * @param effectiveEnd     last day of absence after an early return ({@code actualReturnDate − 1}), else {@code endDate}
 * @param hasSiClaimData   the request carries SI certificate data (ATT-P-05)
 * @param siCertificateType code list SI_CERTIFICATE_TYPE, null when none (not health data)
 */
public record AbsenceChanged(
        UUID tenantId,
        UUID legalEntityId,
        UUID leaveRequestId,
        UUID employmentId,
        UUID personId,
        String leaveTypeCode,
        String leaveCategory,
        String paidBy,
        String payrollCategory,
        String attendanceCode,
        LocalDate startDate,
        LocalDate endDate,
        String startPart,
        String endPart,
        LocalDate actualReturnDate,
        LocalDate effectiveEnd,
        BigDecimal durationDays,
        String status,
        boolean hasSiClaimData,
        String siCertificateType,
        boolean retro) implements AttendanceEvent {

    public static final String TYPE = "att.AbsenceChanged";

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "leave_request";
    }

    @Override
    public UUID aggregateId() {
        return leaveRequestId;
    }

    @Override
    public LocalDate effectiveDate() {
        return startDate;
    }
}
