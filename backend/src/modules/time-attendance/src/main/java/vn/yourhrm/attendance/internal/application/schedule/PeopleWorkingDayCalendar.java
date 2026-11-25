package vn.yourhrm.attendance.internal.application.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.HolidayCalendars;
import vn.yourhrm.people.api.WorkingDayCalendar;

/**
 * T&amp;A's implementation of People's {@link WorkingDayCalendar} SPI (US-PPL-040 notice and temporary-transfer day
 * counts): the legal entity / location calendar's holidays now count as non-working days. The SPI asks per legal
 * entity, not per employee, so the weekly rest day stays People's documented default (Sunday); per-employee
 * schedules are available through {@code WorkSchedules}.
 */
@Component
class PeopleWorkingDayCalendar implements WorkingDayCalendar {

    private final HolidayCalendars holidays;

    PeopleWorkingDayCalendar(HolidayCalendars holidays) {
        this.holidays = holidays;
    }

    @Override
    public boolean isWorkingDay(UUID legalEntityId, UUID locationId, LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return legalEntityId == null || !holidays.isHoliday(legalEntityId, locationId, date);
    }
}
