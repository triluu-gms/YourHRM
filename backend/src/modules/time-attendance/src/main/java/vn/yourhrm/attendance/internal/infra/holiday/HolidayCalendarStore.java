package vn.yourhrm.attendance.internal.infra.holiday;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.holiday_calendar}, {@code att.holiday_date} and {@code att.holiday_calendar_assignment}. Platform
 * VN templates are rows of the sentinel tenant ({@link #GLOBAL_TENANT}); RLS lets the T&amp;A role read them and write
 * only rows of its own tenant, so template rows can never be changed from here (invariant 11).
 * Every method runs inside the caller's T&amp;A transaction.
 */
@Component
public class HolidayCalendarStore {

    /** {@code sys.global_tenant_id()}: owner of the platform templates (DESIGN §3.5). */
    public static final UUID GLOBAL_TENANT = new UUID(0L, 0L);

    public record CalendarRow(UUID id, boolean template, String code, String nameVi, String nameEn,
            String countryCode, String source, UUID templateCalendarId, Integer templateYear, String status,
            int version) {
    }

    /** The business attributes of a holiday date (insert / update). */
    public record DateEntry(LocalDate holidayDate, String dayType, String holidayCode, String nameVi, String nameEn,
            Integer lunarMonth, Integer lunarDay, boolean lunarLeapMonth, LocalDate substituteForDate, boolean paid,
            String eligibility, String nationalityCode, String legalRef) {

        /** Identity of a date inside one calendar (the DDL unique key without the calendar). */
        public String key() {
            return holidayDate + "|" + dayType + "|" + eligibility + "|" + (nationalityCode == null ? "" : nationalityCode);
        }
    }

    public record DateRow(UUID id, UUID calendarId, DateEntry entry, String status, int version) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record AssignmentRow(UUID id, UUID calendarId, UUID legalEntityId, UUID locationId, LocalDate validFrom,
            LocalDate validTo, int version) {

        /** Does the assignment apply on {@code date}? ({@code validTo} is exclusive.) */
        public boolean covers(LocalDate date) {
            return !date.isBefore(validFrom) && (validTo == null || date.isBefore(validTo));
        }
    }

    public record PeriodRef(UUID id, UUID legalEntityId, LocalDate start, LocalDate end, String status) {
    }

    private final AttDb db;

    public HolidayCalendarStore(AttDb db) {
        this.db = db;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    // ------------------------------------------------------------------------------------------------ calendars

    private static final String CALENDAR_COLUMNS = """
            select id, tenant_id = '00000000-0000-0000-0000-000000000000'::uuid, code, name_vi, name_en, country_code,
                   source, template_calendar_id, template_year, status, version
              from att.holiday_calendar""";

    /** The tenant's own calendars (no templates). */
    public List<CalendarRow> calendars() {
        return db.jdbc().sql(CALENDAR_COLUMNS + " where tenant_id = :t order by code")
                .param("t", tenant()).query(HolidayCalendarStore::calendar).list();
    }

    /** The platform templates (sentinel tenant). */
    public List<CalendarRow> templates() {
        return db.jdbc().sql(CALENDAR_COLUMNS + " where tenant_id = :g and status = 'ACTIVE' order by code")
                .param("g", GLOBAL_TENANT).query(HolidayCalendarStore::calendar).list();
    }

    /** Years for which template {@code templateId} has active dates. */
    public List<Integer> templateYears(UUID templateId) {
        return db.jdbc().sql("""
                        select distinct extract(year from holiday_date)::int from att.holiday_date
                         where tenant_id = :g and calendar_id = :c and status = 'ACTIVE' order by 1""")
                .param("g", GLOBAL_TENANT).param("c", templateId).query(Integer.class).list();
    }

    /** A calendar of the tenant (never a template). */
    public Optional<CalendarRow> calendar(UUID id) {
        return db.jdbc().sql(CALENDAR_COLUMNS + " where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(HolidayCalendarStore::calendar).optional();
    }

    public Optional<CalendarRow> template(UUID id) {
        return db.jdbc().sql(CALENDAR_COLUMNS + " where tenant_id = :g and id = :id")
                .param("g", GLOBAL_TENANT).param("id", id).query(HolidayCalendarStore::calendar).optional();
    }

    public UUID insertCalendar(String code, String nameVi, String nameEn, String countryCode, String source,
            UUID templateCalendarId) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.holiday_calendar (tenant_id, id, code, name_vi, name_en, country_code, source,
                                   template_calendar_id, created_by, updated_by)
                            values (:t, :id, :code, :vi, :en, :cc, :src, :tpl, :by, :by)""")
                    .param("t", tenant()).param("id", id).param("code", code).param("vi", nameVi).param("en", nameEn)
                    .param("cc", countryCode).param("src", source).param("tpl", templateCalendarId)
                    .param("by", actor()).update();
        } catch (DataIntegrityViolationException e) {
            throw translate(e, "holiday_calendar_code_uq", "holiday-calendar-code-taken",
                    "A holiday calendar with code " + code + " already exists");
        }
        return id;
    }

    /** @return false when {@code version} is stale */
    public boolean updateCalendar(UUID id, String nameVi, String nameEn, String status, int version) {
        return db.jdbc().sql("""
                        update att.holiday_calendar
                           set name_vi = :vi, name_en = :en, status = :st, updated_by = :by, version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("vi", nameVi).param("en", nameEn).param("st", status).param("by", actor())
                .param("t", tenant()).param("id", id).param("v", version).update() == 1;
    }

    /** Records the last template year applied (never lowers it). */
    public void markTemplateYear(UUID id, int year) {
        db.jdbc().sql("""
                        update att.holiday_calendar
                           set template_year = greatest(coalesce(template_year, :y), :y), updated_by = :by,
                               version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("y", year).param("by", actor()).param("t", tenant()).param("id", id).update();
    }

    private static CalendarRow calendar(ResultSet rs, int n) throws SQLException {
        return new CalendarRow(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class),
                (Integer) rs.getObject(9), rs.getString(10), rs.getInt(11));
    }

    // ------------------------------------------------------------------------------------------------ dates

    private static final String DATE_COLUMNS = """
            select id, calendar_id, holiday_date, day_type, holiday_code, name_vi, name_en, lunar_month, lunar_day,
                   is_lunar_leap_month, substitute_for_date, is_paid, eligibility, nationality_code, legal_ref, status,
                   version
              from att.holiday_date""";

    /**
     * Dates of {@code calendarId} in {@code [from, to]} (tenant calendar, or template when {@code template}),
     * cancelled ones included when {@code includeCancelled}.
     */
    public List<DateRow> dates(UUID calendarId, boolean template, LocalDate from, LocalDate to,
            boolean includeCancelled) {
        return db.jdbc().sql(DATE_COLUMNS + " " + """
                         where tenant_id = :t and calendar_id = :c and holiday_date between :f and :to
                           and (:all or status = 'ACTIVE')
                         order by holiday_date, day_type, eligibility, nationality_code nulls first""")
                .param("t", template ? GLOBAL_TENANT : tenant()).param("c", calendarId).param("f", from)
                .param("to", to).param("all", includeCancelled).query(HolidayCalendarStore::date).list();
    }

    /** Active dates of several tenant calendars in {@code [from, to]}. */
    public List<DateRow> activeDates(Collection<UUID> calendarIds, LocalDate from, LocalDate to) {
        if (calendarIds.isEmpty()) {
            return List.of();
        }
        return db.jdbc().sql(DATE_COLUMNS + " " + """
                         where tenant_id = :t and calendar_id in (:c) and holiday_date between :f and :to
                           and status = 'ACTIVE'
                         order by holiday_date, day_type""")
                .param("t", tenant()).param("c", calendarIds).param("f", from).param("to", to)
                .query(HolidayCalendarStore::date).list();
    }

    public Optional<DateRow> date(UUID calendarId, UUID id) {
        return db.jdbc().sql(DATE_COLUMNS + " where tenant_id = :t and calendar_id = :c and id = :id")
                .param("t", tenant()).param("c", calendarId).param("id", id).query(HolidayCalendarStore::date)
                .optional();
    }

    public UUID insertDate(UUID calendarId, DateEntry e) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.holiday_date (tenant_id, id, calendar_id, holiday_date, day_type,
                                   holiday_code, name_vi, name_en, lunar_month, lunar_day, is_lunar_leap_month,
                                   substitute_for_date, is_paid, eligibility, nationality_code, legal_ref,
                                   created_by, updated_by)
                            values (:t, :id, :c, :d, :type, :code, :vi, :en, :lm, :ld, :leap, :sub, :paid, :elig,
                                    :nat, :ref, :by, :by)""")
                    .param("t", tenant()).param("id", id).param("c", calendarId).params(entryParams(e))
                    .param("by", actor()).update();
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex, "holiday_date_uq", "holiday-date-exists",
                    "The calendar already has a " + e.dayType() + " entry on " + e.holidayDate()
                            + " for the same employees");
        }
        return id;
    }

    /** Replaces the attributes of a date and makes it ACTIVE. @return false when {@code version} is stale */
    public boolean updateDate(UUID calendarId, UUID id, DateEntry e, int version) {
        try {
            return db.jdbc().sql("""
                            update att.holiday_date
                               set holiday_date = :d, day_type = :type, holiday_code = :code, name_vi = :vi,
                                   name_en = :en, lunar_month = :lm, lunar_day = :ld, is_lunar_leap_month = :leap,
                                   substitute_for_date = :sub, is_paid = :paid, eligibility = :elig,
                                   nationality_code = :nat, legal_ref = :ref, status = 'ACTIVE', updated_by = :by,
                                   version = version + 1
                             where tenant_id = :t and calendar_id = :c and id = :id and version = :v""")
                    .params(entryParams(e)).param("by", actor()).param("t", tenant()).param("c", calendarId)
                    .param("id", id).param("v", version).update() == 1;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex, "holiday_date_uq", "holiday-date-exists",
                    "The calendar already has a " + e.dayType() + " entry on " + e.holidayDate()
                            + " for the same employees");
        }
    }

    /** @return false when {@code version} is stale */
    public boolean setDateStatus(UUID calendarId, UUID id, String status, int version) {
        return db.jdbc().sql("""
                        update att.holiday_date set status = :st, updated_by = :by, version = version + 1
                         where tenant_id = :t and calendar_id = :c and id = :id and version = :v""")
                .param("st", status).param("by", actor()).param("t", tenant()).param("c", calendarId)
                .param("id", id).param("v", version).update() == 1;
    }

    /** Is there an ACTIVE PUBLIC date on {@code date} in the calendar (the holiday a SUBSTITUTE replaces)? */
    public boolean hasActivePublicDate(UUID calendarId, LocalDate date) {
        return db.jdbc().sql("""
                        select exists (select 1 from att.holiday_date
                                        where tenant_id = :t and calendar_id = :c and holiday_date = :d
                                          and day_type = 'PUBLIC' and status = 'ACTIVE')""")
                .param("t", tenant()).param("c", calendarId).param("d", date).query(Boolean.class).single();
    }

    /** Active SUBSTITUTE dates that replace {@code date} (blocks cancelling or moving that holiday). */
    public List<LocalDate> activeSubstitutesOf(UUID calendarId, LocalDate date) {
        return db.jdbc().sql("""
                        select holiday_date from att.holiday_date
                         where tenant_id = :t and calendar_id = :c and substitute_for_date = :d
                           and day_type = 'SUBSTITUTE' and status = 'ACTIVE' order by 1""")
                .param("t", tenant()).param("c", calendarId).param("d", date).query(LocalDate.class).list();
    }

    private static java.util.Map<String, Object> entryParams(DateEntry e) {
        var p = new java.util.HashMap<String, Object>();
        p.put("d", e.holidayDate());
        p.put("type", e.dayType());
        p.put("code", e.holidayCode());
        p.put("vi", e.nameVi());
        p.put("en", e.nameEn());
        p.put("lm", e.lunarMonth());
        p.put("ld", e.lunarDay());
        p.put("leap", e.lunarLeapMonth());
        p.put("sub", e.substituteForDate());
        p.put("paid", e.paid());
        p.put("elig", e.eligibility());
        p.put("nat", e.nationalityCode());
        p.put("ref", e.legalRef());
        return p;
    }

    private static DateRow date(ResultSet rs, int n) throws SQLException {
        DateEntry e = new DateEntry(rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), (Integer) shortToInt(rs.getObject(8)),
                (Integer) shortToInt(rs.getObject(9)), rs.getBoolean(10), rs.getObject(11, LocalDate.class),
                rs.getBoolean(12), rs.getString(13), rs.getString(14), rs.getString(15));
        return new DateRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), e, rs.getString(16),
                rs.getInt(17));
    }

    private static Object shortToInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    // ------------------------------------------------------------------------------------------------ assignments

    private static final String ASSIGNMENT_COLUMNS = """
            select id, calendar_id, legal_entity_id, location_id, valid_from, valid_to, version
              from att.holiday_calendar_assignment""";

    public List<AssignmentRow> assignments(UUID calendarId) {
        return db.jdbc().sql(ASSIGNMENT_COLUMNS + " " + """
                         where tenant_id = :t and calendar_id = :c
                         order by legal_entity_id, location_id nulls first, valid_from""")
                .param("t", tenant()).param("c", calendarId).query(HolidayCalendarStore::assignment).list();
    }

    /** All assignments of one legal entity (every location and the entity-wide ones). */
    public List<AssignmentRow> assignmentsOfLegalEntity(UUID legalEntityId) {
        return db.jdbc().sql(ASSIGNMENT_COLUMNS + " " + """
                         where tenant_id = :t and legal_entity_id = :le
                         order by location_id nulls first, valid_from""")
                .param("t", tenant()).param("le", legalEntityId).query(HolidayCalendarStore::assignment).list();
    }

    /**
     * Assignments of the legal entity overlapping {@code [from, to]}: the entity-wide ones and, when
     * {@code locationId} is given, those of that location.
     */
    public List<AssignmentRow> assignmentsFor(UUID legalEntityId, UUID locationId, LocalDate from, LocalDate to) {
        return db.jdbc().sql(ASSIGNMENT_COLUMNS + " " + """
                         where tenant_id = :t and legal_entity_id = :le
                           and (location_id is null or location_id = cast(:loc as uuid))
                           and valid_during && daterange(:f, cast(:to as date), '[]')
                         order by valid_from""")
                .param("t", tenant()).param("le", legalEntityId).param("loc", locationId).param("f", from)
                .param("to", to).query(HolidayCalendarStore::assignment).list();
    }

    public Optional<AssignmentRow> assignment(UUID calendarId, UUID id) {
        return db.jdbc().sql(ASSIGNMENT_COLUMNS + " where tenant_id = :t and calendar_id = :c and id = :id")
                .param("t", tenant()).param("c", calendarId).param("id", id)
                .query(HolidayCalendarStore::assignment).optional();
    }

    public UUID insertAssignment(UUID calendarId, UUID legalEntityId, UUID locationId, LocalDate validFrom,
            LocalDate validTo) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into att.holiday_calendar_assignment (tenant_id, id, calendar_id, legal_entity_id,
                                   location_id, valid_from, valid_to, created_by, updated_by)
                            values (:t, :id, :c, :le, :loc, :f, :to, :by, :by)""")
                    .param("t", tenant()).param("id", id).param("c", calendarId).param("le", legalEntityId)
                    .param("loc", locationId).param("f", validFrom).param("to", validTo).param("by", actor())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw overlap(e);
        }
        return id;
    }

    /** @return false when {@code version} is stale */
    public boolean updateAssignmentEnd(UUID calendarId, UUID id, LocalDate validTo, int version) {
        try {
            return db.jdbc().sql("""
                            update att.holiday_calendar_assignment
                               set valid_to = :to, updated_by = :by, version = version + 1
                             where tenant_id = :t and calendar_id = :c and id = :id and version = :v""")
                    .param("to", validTo).param("by", actor()).param("t", tenant()).param("c", calendarId)
                    .param("id", id).param("v", version).update() == 1;
        } catch (DataIntegrityViolationException e) {
            throw overlap(e);
        }
    }

    private static ApiException overlap(DataIntegrityViolationException e) {
        return translate(e, "holiday_calendar_assignment_ex", "holiday-calendar-assignment-overlap",
                "Another holiday calendar is already assigned to this legal entity / location in that period");
    }

    private static AssignmentRow assignment(ResultSet rs, int n) throws SQLException {
        return new AssignmentRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                rs.getInt(7));
    }

    // ------------------------------------------------------------------------------------------------ periods

    /**
     * CLOSED / LOCKED attendance periods of {@code legalEntityIds} overlapping {@code [from, to]} ({@code to} null =
     * open-ended). BR-ATT-006: those days are not recalculated in place.
     */
    public List<PeriodRef> frozenPeriods(Collection<UUID> legalEntityIds, LocalDate from, LocalDate to) {
        if (legalEntityIds.isEmpty()) {
            return List.of();
        }
        return db.jdbc().sql("""
                        select id, legal_entity_id, period_start, period_end, status from att.attendance_period
                         where tenant_id = :t and legal_entity_id in (:les) and status in ('CLOSED','LOCKED')
                           and period_range && daterange(:f, cast(:to as date), '[]')
                         order by period_start""")
                .param("t", tenant()).param("les", legalEntityIds).param("f", from).param("to", to)
                .query((rs, n) -> new PeriodRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getString(5)))
                .list();
    }

    // ------------------------------------------------------------------------------------------------ errors

    private static ApiException translate(DataIntegrityViolationException e, String constraint, String code,
            String message) {
        String detail = String.valueOf(e.getMostSpecificCause().getMessage());
        if (detail.contains(constraint)) {
            return ApiException.conflict(code, message);
        }
        throw e;
    }
}
