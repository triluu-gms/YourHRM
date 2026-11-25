package vn.yourhrm.attendance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code att.LeaveEncashmentFlagged} (ATT-P-06, BR-ATT-060): unused leave left over by a leave run is handed to
 * Payroll under the policy's expiry action. Days only, never money (the payout base is Payroll's, Q7).
 * <ul>
 *   <li><b>ENCASH</b>: the days were paid out (an ENCASH ledger row dated {@code ledgerDate}); they are also in the
 *       Attendance Result of the period covering that date as {@code LEAVE_ENCASH_DAYS}, or as a retro delta when
 *       that period is already closed. Payroll pays the result lines; this event is the notice.</li>
 *   <li><b>FLAG</b>: the days were flagged for a decision (HR adjusts the balance or Payroll pays them); they stay on
 *       the balance and are not in any result.</li>
 * </ul>
 * Termination payouts are not announced here: they travel in the leaver's early-close result
 * ({@link LeaverAttendanceClosed}).
 *
 * @param run        YEAR_END (days above the carry-over maximum) or CARRY_EXPIRY (carried days not taken in time)
 * @param outcome    ENCASH or FLAG
 * @param ledgerId   the ledger row (ENCASH row, or the zero-quantity marker of a FLAG)
 */
public record LeaveEncashmentFlagged(
        UUID tenantId,
        UUID legalEntityId,
        UUID employmentId,
        UUID leaveTypeId,
        String leaveTypeCode,
        int leaveYear,
        String run,
        String outcome,
        BigDecimal days,
        LocalDate ledgerDate,
        UUID ledgerId,
        boolean retro) implements AttendanceEvent {

    public static final String TYPE = "att.LeaveEncashmentFlagged";

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
        return ledgerDate;
    }
}
