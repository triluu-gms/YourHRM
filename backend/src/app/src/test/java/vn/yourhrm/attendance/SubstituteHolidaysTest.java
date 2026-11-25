package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.holiday.SubstituteHolidays;
import vn.yourhrm.attendance.internal.domain.holiday.SubstituteHolidays.Substitute;

/** BR-ATT-003 / US-ATT-004: substitute days against the employee's OWN weekly rest days. */
class SubstituteHolidaysTest {

    static final Predicate<LocalDate> SAT_SUN = d -> d.getDayOfWeek() == DayOfWeek.SATURDAY
            || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    static final Predicate<LocalDate> SUNDAY = d -> d.getDayOfWeek() == DayOfWeek.SUNDAY;
    static final Predicate<LocalDate> TUESDAY = d -> d.getDayOfWeek() == DayOfWeek.TUESDAY;

    static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void holidayOnAWorkingDayNeedsNoSubstitute() {
        // 2027-09-02 is a Thursday
        assertThat(SubstituteHolidays.derive(List.of(d("2027-09-02")), SAT_SUN)).isEmpty();
    }

    @Test
    void holidayOnSundayMovesToMonday() {
        // 2026-08-30 Sunday (synthetic holiday)
        assertThat(SubstituteHolidays.derive(List.of(d("2026-08-30")), SAT_SUN))
                .containsExactly(new Substitute(d("2026-08-31"), d("2026-08-30")));
    }

    @Test
    void theEmployeesOwnRestDayCountsNotTheWeekday() {
        // 2026-09-01 is a Tuesday: only the worker whose rest day is Tuesday gets a substitute (Wednesday)
        assertThat(SubstituteHolidays.derive(List.of(d("2026-09-01")), SAT_SUN)).isEmpty();
        assertThat(SubstituteHolidays.derive(List.of(d("2026-09-01")), TUESDAY))
                .containsExactly(new Substitute(d("2026-09-02"), d("2026-09-01")));
    }

    @Test
    void consecutiveHolidaysPushSubstitutesPastTheBlock() {
        // Tết block Sat 2027-02-06 .. Wed 2027-02-10; Sat and Sun are rest days for a 5-day week
        List<LocalDate> tet = List.of(d("2027-02-06"), d("2027-02-07"), d("2027-02-08"), d("2027-02-09"),
                d("2027-02-10"));
        assertThat(SubstituteHolidays.derive(tet, SAT_SUN)).containsExactly(
                new Substitute(d("2027-02-11"), d("2027-02-06")),
                new Substitute(d("2027-02-12"), d("2027-02-07")));
    }

    @Test
    void aSaturdayHalfDayIsAWorkingDayForASixDayWeek() {
        // holiday Sunday 2026-08-30 is a rest day; for a Mon-Sat week the next working day is Monday;
        // a holiday on Sunday followed by a Monday holiday lands on Tuesday
        assertThat(SubstituteHolidays.derive(List.of(d("2026-08-30"), d("2026-08-31")), SUNDAY))
                .containsExactly(new Substitute(d("2026-09-01"), d("2026-08-30")));
    }

    @Test
    void twoHolidaysOnTheSameWeekendGetDistinctSubstitutes() {
        assertThat(SubstituteHolidays.derive(List.of(d("2026-08-29"), d("2026-08-30")), SAT_SUN)).containsExactly(
                new Substitute(d("2026-08-31"), d("2026-08-29")),
                new Substitute(d("2026-09-01"), d("2026-08-30")));
    }

    @Test
    void aScheduleWithoutWorkingDaysYieldsNoSubstitute() {
        assertThat(SubstituteHolidays.derive(List.of(d("2026-08-30")), x -> true)).isEmpty();
    }
}
