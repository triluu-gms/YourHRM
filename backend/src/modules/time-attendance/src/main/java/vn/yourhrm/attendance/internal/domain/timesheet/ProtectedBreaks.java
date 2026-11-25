package vn.yourhrm.attendance.internal.domain.timesheet;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Statutory paid short breaks of protected workers (BR-ATT-035, VN-16): pregnancy from month 7, raising a child under
 * 12 months, and the menstrual break. Pure rules, no I/O; the minutes come from the Legal Parameter Service.
 */
public final class ProtectedBreaks {

    private ProtectedBreaks() {
    }

    /**
     * The credited minutes of a day: the larger one applies, the entitlements are never added up (BRD BR-ATT-035).
     * Null or negative candidates (no entitlement, parameter missing) count as 0.
     */
    public static int larger(Integer... candidates) {
        int max = 0;
        for (Integer c : candidates) {
            if (c != null && c > max) {
                max = c;
            }
        }
        return max;
    }

    /**
     * The declared menstrual-break days that are credited: per calendar month the first {@code perMonth} ACTIVE
     * declarations in date order (deterministic, so a lowered limit or a late declaration never credits more than the
     * monthly number). {@code perMonth <= 0} credits nothing.
     */
    public static Set<LocalDate> credited(Collection<LocalDate> declared, int perMonth) {
        Set<LocalDate> out = new TreeSet<>();
        if (perMonth <= 0 || declared == null) {
            return out;
        }
        Map<YearMonth, Integer> used = new HashMap<>();
        for (LocalDate d : new TreeSet<>(declared)) {
            int n = used.merge(YearMonth.from(d), 1, Integer::sum);
            if (n <= perMonth) {
                out.add(d);
            }
        }
        return out;
    }

    /** True when one more day may be declared in a month that already has {@code activeInMonth} ACTIVE days. */
    public static boolean canDeclare(long activeInMonth, int perMonth) {
        return perMonth > 0 && activeInMonth < perMonth;
    }
}
