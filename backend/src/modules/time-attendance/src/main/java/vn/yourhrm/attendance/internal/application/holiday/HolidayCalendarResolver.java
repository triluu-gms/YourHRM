package vn.yourhrm.attendance.internal.application.holiday;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.HolidayCalendars;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.AssignmentRow;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.DateEntry;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * {@link HolidayCalendars} (ATT-P-16): resolves, day by day, the calendar assigned to the location (if any) or else to
 * the whole legal entity, and returns that calendar's active dates. No permission check and no entitlement check
 * (always-on baseline API, R2-18).
 */
@Service
class HolidayCalendarResolver implements HolidayCalendars {

    private final HolidayCalendarStore store;

    HolidayCalendarResolver(HolidayCalendarStore store) {
        this.store = store;
    }

    @Override
    @AttTransactional(readOnly = true)
    public Optional<UUID> calendarFor(UUID legalEntityId, UUID locationId, LocalDate date) {
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(date, "date");
        return Optional.ofNullable(effective(store.assignmentsFor(legalEntityId, locationId, date, date), locationId,
                date));
    }

    @Override
    @AttTransactional(readOnly = true)
    public List<Holiday> holidays(UUID legalEntityId, UUID locationId, LocalDate from, LocalDate to,
            String nationalityCode) {
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.unprocessable("invalid-range", "Give a date range with from <= to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("range-too-long",
                    "A holiday lookup spans at most " + MAX_RANGE_DAYS + " days");
        }
        List<AssignmentRow> assignments = store.assignmentsFor(legalEntityId, locationId, from, to);
        if (assignments.isEmpty()) {
            return List.of();
        }
        String nationality = HolidayDateRules.upper(nationalityCode);
        List<UUID> calendars = assignments.stream().map(AssignmentRow::calendarId).distinct().toList();
        return store.activeDates(calendars, from, to).stream()
                .filter(r -> r.calendarId().equals(effective(assignments, locationId, r.entry().holidayDate())))
                .filter(r -> entitled(r.entry(), nationality))
                .map(r -> {
                    DateEntry e = r.entry();
                    return new Holiday(e.holidayDate(), e.dayType(), e.holidayCode(), e.nameVi(), e.nameEn(),
                            e.substituteForDate(), e.paid(), "FOREIGNERS".equals(e.eligibility()),
                            e.nationalityCode(), r.calendarId());
                })
                .toList();
    }

    /** Overridden so the lookup runs in a T&amp;A transaction (a default method would self-call {@link #holidays}). */
    @Override
    @AttTransactional(readOnly = true)
    public boolean isHoliday(UUID legalEntityId, UUID locationId, LocalDate date) {
        return holidays(legalEntityId, locationId, date, date, null).stream().anyMatch(h -> !h.foreignersOnly());
    }

    /** The calendar in effect on {@code date}: the location's assignment wins over the legal entity's. */
    static UUID effective(List<AssignmentRow> assignments, UUID locationId, LocalDate date) {
        UUID entityWide = null;
        for (AssignmentRow a : assignments) {
            if (!a.covers(date)) {
                continue;
            }
            if (a.locationId() == null) {
                entityWide = a.calendarId();
            } else if (a.locationId().equals(locationId)) {
                return a.calendarId();
            }
        }
        return entityWide;
    }

    /** Days for foreigners only when the employee's nationality is given, not VN and matches (BR-ATT-005). */
    static boolean entitled(DateEntry e, String nationality) {
        if (!"FOREIGNERS".equals(e.eligibility())) {
            return true;
        }
        return nationality != null && !"VN".equals(nationality)
                && (e.nationalityCode() == null || e.nationalityCode().equals(nationality));
    }
}
