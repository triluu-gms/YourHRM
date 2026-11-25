package vn.yourhrm.attendance.internal.domain.holiday;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Per-employee substitute days (BR-ATT-003, BLLĐ Điều 112(3) [verify-legal]): when a public holiday falls on one of the
 * employee's OWN weekly rest days (from the employee's schedule, not the weekday of the calendar), the employee gets the
 * next working day off instead.
 *
 * <p>Rules:
 * <ul>
 *   <li>Holidays are handled in date order; the substitute of a holiday is the first day after it that is not a rest
 *       day, not itself a holiday and not already the substitute of an earlier holiday. Consecutive holidays (Tết)
 *       therefore push their substitutes past the end of the holiday block, one working day each.</li>
 *   <li>A half working day (e.g. Saturday morning in a 5.5-day week) is a working day: the predicate decides.</li>
 *   <li>Only holidays the employee is entitled to are passed in (foreigner days only for foreigners).</li>
 * </ul>
 * Pure and deterministic: the same inputs give the same output (BR-ATT-038).
 */
public final class SubstituteHolidays {

    /** Safety bound: a schedule with no working day within this many days yields no substitute. */
    static final int MAX_SEARCH_DAYS = 31;

    /** A derived substitute day. */
    public record Substitute(LocalDate date, LocalDate substituteFor) {
    }

    private SubstituteHolidays() {
    }

    /** {@link #derive(Collection, Predicate, Predicate)} where every day that is not a rest day is a working day. */
    public static List<Substitute> derive(Collection<LocalDate> holidays, Predicate<LocalDate> isRestDay) {
        return derive(holidays, isRestDay, isRestDay.negate());
    }

    /**
     * @param holidays     the employee's public holidays (any order, duplicates ignored)
     * @param isRestDay    the employee's weekly rest days per date (from the employee's schedule): a holiday on one
     *                     of them gets a substitute
     * @param isWorkingDay the employee's scheduled working days: the substitute is the next one of them (a free
     *                     non-rest day, e.g. the Saturday off of an alternating week, is skipped)
     * @return the substitute days, ordered by the holiday they replace
     */
    public static List<Substitute> derive(Collection<LocalDate> holidays, Predicate<LocalDate> isRestDay,
            Predicate<LocalDate> isWorkingDay) {
        TreeSet<LocalDate> ordered = new TreeSet<>(holidays);
        Set<LocalDate> taken = new TreeSet<>();
        List<Substitute> out = new ArrayList<>();
        for (LocalDate holiday : ordered) {
            if (!isRestDay.test(holiday)) {
                continue;
            }
            LocalDate candidate = holiday.plusDays(1);
            for (int i = 0; i < MAX_SEARCH_DAYS; i++, candidate = candidate.plusDays(1)) {
                if (isWorkingDay.test(candidate) && !ordered.contains(candidate) && !taken.contains(candidate)) {
                    taken.add(candidate);
                    out.add(new Substitute(candidate, holiday));
                    break;
                }
            }
        }
        return out;
    }
}
