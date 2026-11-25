package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read API of the resolved holiday calendar (ATT-P-16, BR-ATT-001/002/005). T&amp;A is the single owner of holiday
 * calendars (R2-02 §5 #2); Core, Payroll, People and Recruitment read them here. Always on: it does not depend on a
 * T&amp;A subscription (R2-18, R2-C9). Evaluated in the current {@code TenantContext}; no permission check (in-process
 * module API, the caller authorises its own use case).
 *
 * <p>Resolution: for each day, the calendar assigned to the location wins over the calendar assigned to the whole legal
 * entity. A day with no assigned calendar has no holidays (there is no implicit national default; tenants assign a copy
 * of the VN template at provisioning).
 *
 * <p>Per-employee substitute days for holidays on the employee's own weekly rest days (BR-ATT-003) depend on the
 * employee's schedule and are derived in daily processing; calendar-level {@code SUBSTITUTE} rows are returned as
 * stored.
 */
public interface HolidayCalendars {

    /** Maximum span of one {@link #holidays} call (inclusive days). */
    int MAX_RANGE_DAYS = 400;

    /**
     * One holiday of a resolved calendar.
     *
     * @param dayType           PUBLIC, SUBSTITUTE or COMPANY (SWAP_OFF / SWAP_WORK are Phase 2)
     * @param holidayCode       e.g. NEW_YEAR, TET_AL, HUNG_KINGS, REUNIFICATION, LABOUR, NATIONAL_DAY; null for company days
     * @param substituteForDate for SUBSTITUTE: the holiday that fell on a rest day
     * @param paid              paid holiday (holiday pay / HOLIDAY_DAYS)
     * @param foreignersOnly    only for foreign employees of {@link #nationalityCode} (BR-ATT-005)
     */
    record Holiday(LocalDate date, String dayType, String holidayCode, String nameVi, String nameEn,
            LocalDate substituteForDate, boolean paid, boolean foreignersOnly, String nationalityCode,
            UUID calendarId) {
    }

    /** The calendar in effect for the legal entity (and location, nullable) on {@code date}. */
    Optional<UUID> calendarFor(UUID legalEntityId, UUID locationId, LocalDate date);

    /**
     * Holidays in {@code [from, to]} for an employee of {@code legalEntityId} working at {@code locationId} (nullable).
     * {@code nationalityCode} (ISO 3166-1 alpha-2, nullable): rows for foreign employees are included only when it is
     * given, differs from VN and matches the row. Ordered by date. At most {@link #MAX_RANGE_DAYS} days.
     */
    List<Holiday> holidays(UUID legalEntityId, UUID locationId, LocalDate from, LocalDate to, String nationalityCode);

    /** Is {@code date} a (paid or unpaid) holiday for all employees of the legal entity / location? */
    default boolean isHoliday(UUID legalEntityId, UUID locationId, LocalDate date) {
        return holidays(legalEntityId, locationId, date, date, null).stream().anyMatch(h -> !h.foreignersOnly());
    }
}
