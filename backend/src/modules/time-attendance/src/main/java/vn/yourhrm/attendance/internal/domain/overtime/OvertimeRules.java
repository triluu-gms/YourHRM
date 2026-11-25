package vn.yourhrm.attendance.internal.domain.overtime;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Bucket;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Category;

/**
 * The pure rules of an overtime request (BR-ATT-044/045/046/048): cap levels, protected-worker outcome and the paid
 * minutes. Every limit is an input (Legal Parameter Service / tenant settings); nothing statutory is a constant here.
 */
public final class OvertimeRules {

    private OvertimeRules() {
    }

    // ------------------------------------------------------------------------------------------------ caps

    public enum Level { OK, WARN, EXCEEDED }

    /**
     * A cap after the request is added.
     *
     * @param kind       DAY, MONTH or YEAR
     * @param capMin     the limit in minutes
     * @param usedMin    minutes already counted before this request (approved + pending, + opening for YEAR)
     * @param requestMin the request's minutes
     */
    public record CapStatus(String kind, int capMin, int usedMin, int requestMin, Level level) {

        public int afterMin() {
            return usedMin + requestMin;
        }

        /** Minutes of the request above the cap (0 when within). */
        public int excessMin() {
            return Math.max(0, Math.min(requestMin, afterMin() - capMin));
        }
    }

    /**
     * Level of a cap: EXCEEDED above the cap, WARN at or above {@code warnPercent} of it (BR-ATT-046), else OK.
     */
    public static CapStatus cap(String kind, int capMin, int usedMin, int requestMin, int warnPercent) {
        int after = usedMin + requestMin;
        Level level = after > capMin ? Level.EXCEEDED
                : (long) after * 100 >= (long) capMin * warnPercent ? Level.WARN : Level.OK;
        return new CapStatus(kind, capMin, usedMin, requestMin, level);
    }

    /**
     * The daily limit (BR-ATT-045): on a working day normal hours + overtime may not exceed {@code maxTotalMin}; on a
     * weekly rest day or holiday the overtime alone may not exceed {@code maxOffDayMin}. No warning threshold.
     */
    public static CapStatus daily(boolean workingDay, int normalMin, int otOfDayMin, int requestMin, int maxTotalMin,
            int maxOffDayMin) {
        int used = workingDay ? normalMin + otOfDayMin : otOfDayMin;
        int cap = workingDay ? maxTotalMin : maxOffDayMin;
        return cap("DAY", cap, used, requestMin, 101);
    }

    // ------------------------------------------------------------------------------------------- protected

    /** What the legal pack says about overtime for one protected-worker type. */
    public enum ProtectedRule { ALLOW, CONSENT, BLOCK }

    public enum ProtectedOutcome { NOT_PROTECTED, ALLOWED, NEEDS_OWN_CONSENT, BLOCKED }

    /**
     * BR-ATT-048: the strictest rule among the employee's protected types on the work date. A type the pack does not
     * define is treated as BLOCK (fail closed). {@code CONSENT} passes only with the employee's own consent (ESS or
     * signed paper), never with consent recorded by the manager.
     */
    public static ProtectedOutcome protectedOutcome(Set<String> activeTypes, Map<String, ProtectedRule> rules,
            boolean ownConsent) {
        if (activeTypes.isEmpty()) {
            return ProtectedOutcome.NOT_PROTECTED;
        }
        ProtectedRule strictest = ProtectedRule.ALLOW;
        for (String t : activeTypes) {
            ProtectedRule r = rules.getOrDefault(t, ProtectedRule.BLOCK);
            if (r.ordinal() > strictest.ordinal()) {
                strictest = r;
            }
        }
        return switch (strictest) {
            case ALLOW -> ProtectedOutcome.ALLOWED;
            case CONSENT -> ownConsent ? ProtectedOutcome.ALLOWED : ProtectedOutcome.NEEDS_OWN_CONSENT;
            case BLOCK -> ProtectedOutcome.BLOCKED;
        };
    }

    // ------------------------------------------------------------------------------------------------ paid

    /** One {@code att.overtime_result} row. */
    public record ResultRow(Category category, int approvedMin, int actualMin, int paidMin, boolean precededByDayOt) {
    }

    /**
     * BR-ATT-044: per category, paid = min(approved, actual), or approved when the policy pays approved overtime as
     * worked (employees exempt from time recording). The night flag follows the minutes that are paid (the actual
     * minutes when there are any, else the approved ones) and is kept when nothing is paid yet, so that a later
     * recalculation from the stored rows keeps it.
     */
    public static List<ResultRow> paid(List<Bucket> approved, List<Bucket> actual, boolean paidAsApproved) {
        Map<Category, Bucket> a = index(approved);
        Map<Category, Bucket> w = index(actual);
        List<ResultRow> out = new ArrayList<>();
        for (Category c : Category.values()) {
            Bucket ap = a.get(c);
            Bucket ac = w.get(c);
            if (ap == null && ac == null) {
                continue;
            }
            int approvedMin = ap == null ? 0 : ap.minutes();
            int actualMin = ac == null ? 0 : ac.minutes();
            int paid = paidAsApproved ? approvedMin : Math.min(approvedMin, actualMin);
            Bucket basis = paidAsApproved || ac == null ? ap : ac;
            boolean flag = basis != null && basis.precededByDayOt();
            out.add(new ResultRow(c, approvedMin, actualMin, paid, flag));
        }
        return out;
    }

    private static Map<Category, Bucket> index(List<Bucket> buckets) {
        Map<Category, Bucket> m = new EnumMap<>(Category.class);
        buckets.forEach(b -> m.put(b.category(), b));
        return m;
    }
}
