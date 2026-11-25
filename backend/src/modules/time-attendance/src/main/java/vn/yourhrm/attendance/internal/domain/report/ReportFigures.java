package vn.yourhrm.attendance.internal.domain.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure figures of the overtime and leave reports (BRD 03 §8 R-04, R-06): cap usage in hours and percent, overtime
 * minutes per category, and the movements of a leave balance folded from its ledger rows.
 */
public final class ReportFigures {

    /** The six legal overtime categories (BR-ATT-042), in report order. */
    public static final List<String> OT_CATEGORIES = List.of("OT-WD", "OT-RD", "OT-PH", "OT-WD-N", "OT-RD-N",
            "OT-PH-N");

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ReportFigures() {
    }

    /** Minutes as hours, 2 decimals. */
    public static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(SIXTY, 2, RoundingMode.HALF_UP);
    }

    /** {@code used / cap} in percent, 1 decimal; null without a cap. */
    public static BigDecimal percent(long used, long cap) {
        if (cap <= 0) {
            return null;
        }
        return BigDecimal.valueOf(used).multiply(HUNDRED).divide(BigDecimal.valueOf(cap), 1, RoundingMode.HALF_UP);
    }

    /**
     * Usage of one cap (month or year of the ledger, BR-ATT-046).
     *
     * @param usedHours  opening + approved + pending (what the cap check counts)
     * @param paidHours  paid minutes of the overtime results
     * @param overHours  hours above the cap ({@code used − cap}, never negative)
     * @param overrideHours approved above the cap by an authorised override
     */
    public record CapUsage(String regimeCode, BigDecimal capHours, BigDecimal openingHours, BigDecimal approvedHours,
            BigDecimal pendingHours, BigDecimal usedHours, BigDecimal paidHours, BigDecimal overHours,
            BigDecimal overrideHours, BigDecimal usedPercent, boolean breached) {
    }

    public static CapUsage capUsage(String regimeCode, int capMin, int openingMin, int approvedMin, int pendingMin,
            int paidMin, int overrideMin) {
        long used = (long) openingMin + approvedMin + pendingMin;
        long over = Math.max(0, used - capMin);
        return new CapUsage(regimeCode, hours(capMin), hours(openingMin), hours(approvedMin), hours(pendingMin),
                hours(used), hours(paidMin), hours(over), hours(overrideMin), percent(used, capMin), used > capMin);
    }

    /** Hours per OT category, every category present (0 when none), in {@link #OT_CATEGORIES} order. */
    public static Map<String, BigDecimal> otByCategory(Map<String, Long> minutes) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String c : OT_CATEGORIES) {
            out.put(c, hours(minutes.getOrDefault(c, 0L)));
        }
        minutes.forEach((c, m) -> out.putIfAbsent(c, hours(m)));
        return out;
    }

    /**
     * The movements of a leave balance in one leave year up to a date (R-06), folded from the ledger sums per
     * transaction type. CARRY rows are split by sign: {@code +n} carried into this year, {@code −n} carried out of it
     * at year end. Out-flows are shown as positive numbers; {@code closing} is the signed ledger sum (the balance).
     */
    public record LeaveMovements(BigDecimal opening, BigDecimal carriedIn, BigDecimal accrued, BigDecimal taken,
            BigDecimal adjusted, BigDecimal carriedOut, BigDecimal expired, BigDecimal encashed, BigDecimal closing) {
    }

    /**
     * @param sums      Σ quantity per {@code txn_type}, CARRY excluded
     * @param carryIn   Σ of the positive CARRY rows
     * @param carryOut  Σ of the negative CARRY rows (≤ 0)
     */
    public static LeaveMovements leaveMovements(Map<String, BigDecimal> sums, BigDecimal carryIn,
            BigDecimal carryOut) {
        BigDecimal opening = z(sums.get("OPENING"));
        BigDecimal accrued = z(sums.get("ACCRUE"));
        BigDecimal take = z(sums.get("TAKE")).add(z(sums.get("CANCEL")));
        BigDecimal adjusted = z(sums.get("ADJUST"));
        BigDecimal expire = z(sums.get("EXPIRE"));
        BigDecimal encash = z(sums.get("ENCASH"));
        BigDecimal closing = opening.add(z(carryIn)).add(z(carryOut)).add(accrued).add(take).add(adjusted)
                .add(expire).add(encash);
        return new LeaveMovements(opening, z(carryIn), accrued, take.negate(), adjusted, z(carryOut).negate(),
                expire.negate(), encash.negate(), closing);
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
