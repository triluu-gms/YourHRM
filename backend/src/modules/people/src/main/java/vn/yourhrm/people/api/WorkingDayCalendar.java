package vn.yourhrm.people.api;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Consumer-declared SPI for working days (US-PPL-040, Art. 29 temporary transfer: cumulative working days per calendar
 * year and notice in working days). Holiday calendars and work patterns belong to Time &amp; Attendance, which implements
 * it once it exists. Evaluated in the current {@code TenantContext}.
 *
 * <p>Fallback {@link #NONE} (documented, [verify-legal]): every day except Sunday is a working day (Labour Code: one
 * weekly rest day, Sunday by default) and public holidays are NOT known. Effect: the yearly temporary-transfer count
 * is at most over-counted (consent is asked for earlier, never later), while the notice count may include a holiday.
 */
public interface WorkingDayCalendar {

    /** Is {@code date} a working day for an employee of {@code legalEntityId} working at {@code locationId} (nullable)? */
    boolean isWorkingDay(UUID legalEntityId, UUID locationId, LocalDate date);

    /** Monday to Saturday, no holidays (T&amp;A not available). */
    WorkingDayCalendar NONE = new WorkingDayCalendar() {
        @Override
        public boolean isWorkingDay(UUID legalEntityId, UUID locationId, LocalDate date) {
            return date.getDayOfWeek() != DayOfWeek.SUNDAY;
        }

        @Override
        public String toString() {
            return "WorkingDayCalendar.NONE (Monday-Saturday, no public holidays: T&A not available)";
        }
    };
}
