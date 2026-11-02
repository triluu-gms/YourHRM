package vn.yourhrm.people.internal.domain.assignment;

import java.time.LocalDate;
import java.util.function.Predicate;

/** Working-day arithmetic for Art. 29 temporary transfers (US-PPL-040). Pure: the calendar is a predicate. */
public final class WorkingDays {

    private WorkingDays() {
    }

    /** Working days in {@code [from, toExclusive)}; 0 when the range is empty. */
    public static int count(LocalDate from, LocalDate toExclusive, Predicate<LocalDate> isWorkingDay) {
        int n = 0;
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
            if (isWorkingDay.test(d)) {
                n++;
            }
        }
        return n;
    }

    /** Working days of {@code [from, toExclusive)} that fall into calendar year {@code year}. */
    public static int countInYear(LocalDate from, LocalDate toExclusive, int year, Predicate<LocalDate> isWorkingDay) {
        LocalDate yStart = LocalDate.of(year, 1, 1);
        LocalDate yEnd = yStart.plusYears(1);
        LocalDate a = from.isAfter(yStart) ? from : yStart;
        LocalDate b = toExclusive.isBefore(yEnd) ? toExclusive : yEnd;
        return a.isBefore(b) ? count(a, b, isWorkingDay) : 0;
    }

    /** Working days strictly between the notice date and the start date (the notice period, Art. 29). */
    public static int noticeDays(LocalDate noticeDate, LocalDate startDate, Predicate<LocalDate> isWorkingDay) {
        return count(noticeDate.plusDays(1), startDate, isWorkingDay);
    }
}
