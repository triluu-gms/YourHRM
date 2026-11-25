package vn.yourhrm.attendance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Leave settlement of a leaver (BR-ATT-067, ATT-P-09): per balance-tracked leave type, the days still owed to the
 * employee (to be paid out by Payroll) or taken in advance (to be recovered), as of the last working day. Evaluated in
 * the current {@code TenantContext} without a permission check (in-process module API, e.g. the leaver early close).
 * Payroll turns days into money; T&amp;A never does.
 */
public interface LeaveSettlements {

    /**
     * One leave type of the settlement.
     *
     * @param entitledToLastDay the year's entitlement prorated to the last day (ND145), plus opening, carried and
     *                          adjusted days
     * @param taken             days taken in the leave year (approved leave)
     * @param remaining         {@code entitledToLastDay − taken − expired/encashed}: positive = encash, negative = recover
     * @param finalised         the settlement was written to the ledger (ENCASH / ADJUST rows of ref TERMINATION)
     */
    record Line(UUID leaveTypeId, String leaveTypeCode, int leaveYear, BigDecimal entitledToLastDay,
            BigDecimal taken, BigDecimal remaining, boolean finalised) {

        public BigDecimal encashDays() {
            return remaining.signum() > 0 ? remaining : BigDecimal.ZERO;
        }

        public BigDecimal recoverDays() {
            return remaining.signum() < 0 ? remaining.negate() : BigDecimal.ZERO;
        }
    }

    /**
     * @param lastWorkingDate the LWD (else the termination date) the settlement is computed at; null when the employment
     *                        has no end yet (the settlement is then computed as of today, as a projection)
     */
    record Settlement(UUID employmentId, LocalDate lastWorkingDate, List<Line> lines) {
    }

    Settlement settlement(UUID employmentId);
}
