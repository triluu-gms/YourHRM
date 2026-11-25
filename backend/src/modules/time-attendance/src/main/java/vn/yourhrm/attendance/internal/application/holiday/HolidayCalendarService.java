package vn.yourhrm.attendance.internal.application.holiday;

import static vn.yourhrm.attendance.internal.application.holiday.HolidayDateRules.blankToNull;
import static vn.yourhrm.attendance.internal.application.holiday.HolidayDateRules.invalid;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.HolidayCalendarChanged.Change;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.AssignmentRow;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.CalendarRow;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.DateEntry;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.DateRow;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Holiday calendars (BR-ATT-001/002/005/006, US-ATT-001/002/005/006): tenant calendars with effective-dated entries
 * (public holidays, substitute days, company days, days for foreign employees), their effective-dated assignment to a
 * legal entity or one of its locations, and the copy / re-acceptance of the platform VN template per year.
 *
 * <p>Authorisation: every endpoint needs {@code att.holiday_calendar.*} (any scope). Changing a calendar also needs the
 * permission's scope on every legal entity the calendar is (or was) assigned to, and assigning one needs it on the
 * target legal entity. Calendars are configuration: viewing them is not data-scoped.
 *
 * <p>Every change is row-audited by the database; a change or cancellation of a date additionally records its reason
 * as a business action. Every change that affects dates writes {@code att.HolidayCalendarChanged} in the same
 * transaction (BR-ATT-006).
 */
@Service
public class HolidayCalendarService {

    public record AssignmentView(UUID id, UUID calendarId, UUID legalEntityId, UUID locationId, LocalDate validFrom,
            LocalDate validTo, int version) {

        static AssignmentView of(AssignmentRow a) {
            return new AssignmentView(a.id(), a.calendarId(), a.legalEntityId(), a.locationId(), a.validFrom(),
                    a.validTo(), a.version());
        }
    }

    public record CalendarView(UUID id, String code, String nameVi, String nameEn, String countryCode, String source,
            UUID templateCalendarId, Integer templateYear, String status, int version,
            List<AssignmentView> assignments) {
    }

    public record TemplateView(UUID id, String code, String nameVi, String nameEn, String countryCode,
            List<Integer> years) {
    }

    public record HolidayDateView(UUID id, UUID calendarId, LocalDate holidayDate, String dayType, String holidayCode,
            String nameVi, String nameEn, Integer lunarMonth, Integer lunarDay, boolean lunarLeapMonth,
            LocalDate substituteForDate, boolean paid, String eligibility, String nationalityCode, String legalRef,
            String status, int version) {

        static HolidayDateView of(DateRow r) {
            DateEntry e = r.entry();
            return new HolidayDateView(r.id(), r.calendarId(), e.holidayDate(), e.dayType(), e.holidayCode(),
                    e.nameVi(), e.nameEn(), e.lunarMonth(), e.lunarDay(), e.lunarLeapMonth(), e.substituteForDate(),
                    e.paid(), e.eligibility(), e.nationalityCode(), e.legalRef(), r.status(), r.version());
        }
    }

    /** How a template date differs from the tenant calendar (US-ATT-002). */
    public enum DiffKind {
        /** In the template, not in the calendar. */
        ADDED,
        /** In both, with other attributes (template republished, or edited by the tenant); applied only when named. */
        CHANGED,
        /** Taken over from the template earlier, no longer in it. */
        REMOVED,
        /** In the template, cancelled by the tenant: kept cancelled unless accepted explicitly. */
        OVERRIDDEN
    }

    public record DiffItem(String key, DiffKind kind, DateEntry template, HolidayDateView current) {
    }

    public record ApplyResult(int year, List<DiffItem> applied) {
    }

    private final HolidayCalendarStore store;
    private final HolidayChangeEvents events;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;

    public HolidayCalendarService(HolidayCalendarStore store, HolidayChangeEvents events, AuthorizationService authz,
            AuditLog audit, BusinessClock clock) {
        this.store = store;
        this.events = events;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    // ============================================================================================ calendars

    @AttTransactional(readOnly = true)
    public List<CalendarView> list() {
        return store.calendars().stream().map(this::view).toList();
    }

    @AttTransactional(readOnly = true)
    public CalendarView get(UUID id) {
        return view(load(id));
    }

    /** A CUSTOM calendar (no template). */
    @AttTransactional
    public CalendarView create(String code, String nameVi, String nameEn) {
        UUID id = store.insertCalendar(calendarCode(code), name(nameVi), blankToNull(nameEn), "VN", "CUSTOM", null);
        return view(load(id));
    }

    /**
     * A TENANT_COPY of platform template {@code templateId}; with {@code year}, the template dates of that year are
     * copied at once (BR-ATT-002).
     */
    @AttTransactional
    public CalendarView createFromTemplate(UUID templateId, String code, String nameVi, String nameEn, Integer year) {
        CalendarRow template = store.template(templateId).filter(t -> "ACTIVE".equals(t.status()))
                .orElseThrow(() -> ApiException.notFound("Holiday calendar template", templateId));
        UUID id = store.insertCalendar(calendarCode(code), nameVi == null ? template.nameVi() : name(nameVi),
                nameEn == null ? template.nameEn() : blankToNull(nameEn), template.countryCode(), "TENANT_COPY",
                template.id());
        if (year != null) {
            apply(load(id), year(year), null, null);
        }
        return view(load(id));
    }

    /** Names and status. A calendar still assigned today or later cannot be deactivated. */
    @AttTransactional
    public CalendarView update(UUID id, String nameVi, String nameEn, String status, int version) {
        CalendarRow c = load(id);
        requireCalendarScope(c);
        String st = status == null ? c.status() : HolidayDateRules.upper(status);
        if (!Set.of("ACTIVE", "INACTIVE").contains(st)) {
            throw invalid("invalid-status", "The status is ACTIVE or INACTIVE");
        }
        if ("INACTIVE".equals(st) && !"INACTIVE".equals(c.status())) {
            LocalDate today = clock.today();
            boolean inUse = store.assignments(id).stream()
                    .anyMatch(a -> a.validTo() == null || a.validTo().isAfter(today));
            if (inUse) {
                throw ApiException.conflict("holiday-calendar-in-use",
                        "The calendar is assigned to a legal entity today or later; end the assignments first");
            }
        }
        if (!store.updateCalendar(id, nameVi == null ? c.nameVi() : name(nameVi),
                nameEn == null ? c.nameEn() : blankToNull(nameEn), st, version)) {
            throw stale("holiday calendar");
        }
        return view(load(id));
    }

    // ============================================================================================ dates

    @AttTransactional(readOnly = true)
    public List<HolidayDateView> dates(UUID calendarId, int year, boolean includeCancelled) {
        load(calendarId);
        int y = year(year);
        return store.dates(calendarId, false, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), includeCancelled)
                .stream().map(HolidayDateView::of).toList();
    }

    @AttTransactional
    public HolidayDateView addDate(UUID calendarId, DateEntry entry) {
        CalendarRow c = load(calendarId);
        requireCalendarScope(c);
        DateEntry e = HolidayDateRules.validated(entry);
        requireReplacedHoliday(calendarId, e);
        UUID id = store.insertDate(calendarId, e);
        events.datesChanged(c, Change.DATE_ADDED, List.of(e.holidayDate()));
        return HolidayDateView.of(loadDate(calendarId, id));
    }

    /** Replaces a date (also reactivates a cancelled one); the reason is audited (template override, US-ATT-002). */
    @AttTransactional
    public HolidayDateView updateDate(UUID calendarId, UUID dateId, DateEntry entry, int version, String reason) {
        CalendarRow c = load(calendarId);
        requireCalendarScope(c);
        String why = HolidayDateRules.requireReason(reason);
        DateRow current = loadDate(calendarId, dateId);
        DateEntry e = HolidayDateRules.validated(entry);
        boolean movesPublic = current.active() && "PUBLIC".equals(current.entry().dayType())
                && (!e.holidayDate().equals(current.entry().holidayDate()) || !"PUBLIC".equals(e.dayType()));
        if (movesPublic) {
            requireNoSubstitutes(calendarId, current.entry().holidayDate());
        }
        requireReplacedHoliday(calendarId, e);
        if (!store.updateDate(calendarId, dateId, e, version)) {
            throw stale("holiday date");
        }
        audit.record(new BusinessAction("OVERRIDE", "att", "holiday_date", dateId, null,
                Map.of("calendar", c.code(), "date", current.entry().holidayDate().toString(), "reason", why)));
        events.datesChanged(c, Change.DATE_CHANGED, List.of(current.entry().holidayDate(), e.holidayDate()));
        return HolidayDateView.of(loadDate(calendarId, dateId));
    }

    /** Cancels a date (kept as history); the reason is audited. */
    @AttTransactional
    public HolidayDateView cancelDate(UUID calendarId, UUID dateId, int version, String reason) {
        CalendarRow c = load(calendarId);
        requireCalendarScope(c);
        String why = HolidayDateRules.requireReason(reason);
        DateRow current = loadDate(calendarId, dateId);
        if (!current.active()) {
            throw ApiException.conflict("holiday-date-cancelled", "The date is already cancelled");
        }
        if ("PUBLIC".equals(current.entry().dayType())) {
            requireNoSubstitutes(calendarId, current.entry().holidayDate());
        }
        if (!store.setDateStatus(calendarId, dateId, "CANCELLED", version)) {
            throw stale("holiday date");
        }
        audit.record(new BusinessAction("CANCEL", "att", "holiday_date", dateId, null,
                Map.of("calendar", c.code(), "date", current.entry().holidayDate().toString(), "reason", why)));
        events.datesChanged(c, Change.DATE_CANCELLED, List.of(current.entry().holidayDate()));
        return HolidayDateView.of(loadDate(calendarId, dateId));
    }

    // ============================================================================================ templates

    @AttTransactional(readOnly = true)
    public List<TemplateView> templates() {
        return store.templates().stream().map(t -> new TemplateView(t.id(), t.code(), t.nameVi(), t.nameEn(),
                t.countryCode(), store.templateYears(t.id()))).toList();
    }

    @AttTransactional(readOnly = true)
    public List<HolidayDateView> templateDates(UUID templateId, int year) {
        store.template(templateId).orElseThrow(() -> ApiException.notFound("Holiday calendar template", templateId));
        int y = year(year);
        return store.dates(templateId, true, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), false).stream()
                .map(HolidayDateView::of).toList();
    }

    /** Differences between the calendar and its template for {@code year} (US-ATT-002). */
    @AttTransactional(readOnly = true)
    public List<DiffItem> templateDiff(UUID calendarId, int year) {
        return diff(requireCopy(load(calendarId)), year(year));
    }

    /**
     * Accepts template differences of {@code year}: the items of {@code acceptKeys}, or, when null, every ADDED and
     * REMOVED item. CHANGED items (the template or the tenant changed the date: it cannot be told which) and OVERRIDDEN
     * items (dates the tenant cancelled) are only taken over when named, so a default acceptance never reverts a
     * tenant override.
     */
    @AttTransactional
    public ApplyResult applyTemplate(UUID calendarId, int year, List<String> acceptKeys, String reason) {
        CalendarRow c = requireCopy(load(calendarId));
        requireCalendarScope(c);
        return apply(c, year(year), acceptKeys, blankToNull(reason));
    }

    private ApplyResult apply(CalendarRow c, int year, List<String> acceptKeys, String reason) {
        List<DiffItem> diff = diff(c, year);
        List<DiffItem> chosen;
        if (acceptKeys == null) {
            chosen = diff.stream().filter(d -> d.kind() == DiffKind.ADDED || d.kind() == DiffKind.REMOVED).toList();
        } else {
            Map<String, DiffItem> byKey = new LinkedHashMap<>();
            diff.forEach(d -> byKey.put(d.key(), d));
            chosen = new ArrayList<>();
            for (String k : new TreeSet<>(acceptKeys)) {
                DiffItem d = byKey.get(k);
                if (d == null) {
                    throw invalid("unknown-template-difference", "No template difference with key " + k
                            + " for " + year + "; reload the differences");
                }
                chosen.add(d);
            }
        }
        // PUBLIC first, so that substitute days find the holiday they replace
        chosen = chosen.stream().sorted((a, b) -> Boolean.compare(!isPublic(a), !isPublic(b))).toList();
        List<LocalDate> touched = new ArrayList<>();
        for (DiffItem d : chosen) {
            switch (d.kind()) {
                case ADDED -> store.insertDate(c.id(), d.template());
                case CHANGED, OVERRIDDEN -> {
                    if (!store.updateDate(c.id(), d.current().id(), d.template(), d.current().version())) {
                        throw stale("holiday date");
                    }
                }
                case REMOVED -> {
                    if (!store.setDateStatus(c.id(), d.current().id(), "CANCELLED", d.current().version())) {
                        throw stale("holiday date");
                    }
                }
            }
            touched.add(d.template() != null ? d.template().holidayDate() : d.current().holidayDate());
        }
        store.markTemplateYear(c.id(), year);
        if (!chosen.isEmpty()) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("calendar", c.code());
            details.put("year", String.valueOf(year));
            details.put("accepted", String.valueOf(chosen.size()));
            if (reason != null) {
                details.put("reason", reason);
            }
            audit.record(new BusinessAction("APPLY_TEMPLATE", "att", "holiday_calendar", c.id(), null, details));
            events.datesChanged(c, Change.TEMPLATE_APPLIED, touched);
        }
        return new ApplyResult(year, chosen);
    }

    private static boolean isPublic(DiffItem d) {
        DateEntry e = d.template() != null ? d.template() : null;
        return e != null ? "PUBLIC".equals(e.dayType()) : "PUBLIC".equals(d.current().dayType());
    }

    private List<DiffItem> diff(CalendarRow c, int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        List<DateRow> template = store.dates(c.templateCalendarId(), true, from, to, false).stream()
                .filter(r -> HolidayDateRules.PHASE1_DAY_TYPES.contains(r.entry().dayType())).toList();
        Map<String, DateRow> current = new LinkedHashMap<>();
        store.dates(c.id(), false, from, to, true).forEach(r -> current.put(r.entry().key(), r));
        Set<String> templateKeys = new TreeSet<>();
        List<DiffItem> out = new ArrayList<>();
        for (DateRow t : template) {
            String key = t.entry().key();
            templateKeys.add(key);
            DateRow cur = current.get(key);
            if (cur == null) {
                out.add(new DiffItem(key, DiffKind.ADDED, t.entry(), null));
            } else if (!cur.active()) {
                out.add(new DiffItem(key, DiffKind.OVERRIDDEN, t.entry(), HolidayDateView.of(cur)));
            } else if (!cur.entry().equals(t.entry())) {
                out.add(new DiffItem(key, DiffKind.CHANGED, t.entry(), HolidayDateView.of(cur)));
            }
        }
        for (DateRow cur : current.values()) {
            DateEntry e = cur.entry();
            boolean fromTemplate = e.holidayCode() != null && Set.of("PUBLIC", "SUBSTITUTE").contains(e.dayType());
            if (cur.active() && fromTemplate && !templateKeys.contains(e.key())) {
                out.add(new DiffItem(e.key(), DiffKind.REMOVED, null, HolidayDateView.of(cur)));
            }
        }
        out.sort((a, b) -> a.key().compareTo(b.key()));
        return out;
    }

    private CalendarRow requireCopy(CalendarRow c) {
        if (!"TENANT_COPY".equals(c.source()) || c.templateCalendarId() == null) {
            throw invalid("not-a-template-copy", "Calendar " + c.code() + " was not copied from a platform template");
        }
        if (store.template(c.templateCalendarId()).isEmpty()) {
            throw ApiException.notFound("Holiday calendar template", c.templateCalendarId());
        }
        return c;
    }

    // ============================================================================================ assignments

    /** Assigns the calendar to a legal entity, or to one of its locations ({@code locationId}), from a date. */
    @AttTransactional
    public AssignmentView assign(UUID calendarId, UUID legalEntityId, UUID locationId, LocalDate validFrom,
            LocalDate validTo) {
        CalendarRow c = load(calendarId);
        if (!"ACTIVE".equals(c.status())) {
            throw ApiException.conflict("holiday-calendar-inactive", "An inactive calendar cannot be assigned");
        }
        if (legalEntityId == null || validFrom == null) {
            throw invalid("assignment-incomplete", "The legal entity and the start date are required");
        }
        requireLegalEntityScope(legalEntityId);
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw invalid("invalid-period", "The end date (exclusive) must be after the start date");
        }
        UUID id = store.insertAssignment(calendarId, legalEntityId, locationId, validFrom, validTo);
        events.assignmentChanged(c, legalEntityId, validFrom, validTo == null ? null : validTo.minusDays(1));
        return AssignmentView.of(loadAssignment(calendarId, id));
    }

    /** Sets (or clears, with null) the exclusive end date of an assignment. */
    @AttTransactional
    public AssignmentView endAssignment(UUID calendarId, UUID assignmentId, LocalDate validTo, int version) {
        CalendarRow c = load(calendarId);
        AssignmentRow a = loadAssignment(calendarId, assignmentId);
        requireLegalEntityScope(a.legalEntityId());
        if (validTo != null && !validTo.isAfter(a.validFrom())) {
            throw invalid("invalid-period", "The end date (exclusive) must be after the start date "
                    + a.validFrom() + "; an assignment cannot be removed");
        }
        if (!store.updateAssignmentEnd(calendarId, assignmentId, validTo, version)) {
            throw stale("holiday calendar assignment");
        }
        LocalDate oldEnd = a.validTo();
        if (oldEnd == null ? validTo != null : !oldEnd.equals(validTo)) {
            LocalDate from = earlierEnd(oldEnd, validTo);
            LocalDate laterEnd = oldEnd == null || validTo == null ? null : (oldEnd.isAfter(validTo) ? oldEnd : validTo);
            events.assignmentChanged(c, a.legalEntityId(), from, laterEnd == null ? null : laterEnd.minusDays(1));
        }
        return AssignmentView.of(loadAssignment(calendarId, assignmentId));
    }

    /** Assignments of one legal entity, all calendars (the "which calendar applies when" view). */
    @AttTransactional(readOnly = true)
    public List<AssignmentView> assignmentsOfLegalEntity(UUID legalEntityId) {
        return store.assignmentsOfLegalEntity(legalEntityId).stream().map(AssignmentView::of).toList();
    }

    private static LocalDate earlierEnd(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    // ============================================================================================ helpers

    private CalendarView view(CalendarRow c) {
        return new CalendarView(c.id(), c.code(), c.nameVi(), c.nameEn(), c.countryCode(), c.source(),
                c.templateCalendarId(), c.templateYear(), c.status(), c.version(),
                store.assignments(c.id()).stream().map(AssignmentView::of).toList());
    }

    private CalendarRow load(UUID id) {
        return store.calendar(id).orElseThrow(() -> ApiException.notFound("Holiday calendar", id));
    }

    private DateRow loadDate(UUID calendarId, UUID id) {
        return store.date(calendarId, id).orElseThrow(() -> ApiException.notFound("Holiday date", id));
    }

    private AssignmentRow loadAssignment(UUID calendarId, UUID id) {
        return store.assignment(calendarId, id)
                .orElseThrow(() -> ApiException.notFound("Holiday calendar assignment", id));
    }

    /** The permission's scope must cover every legal entity that uses (or used) the calendar. */
    private void requireCalendarScope(CalendarRow c) {
        store.assignments(c.id()).stream().map(AssignmentRow::legalEntityId).distinct()
                .forEach(this::requireLegalEntityScope);
    }

    private void requireLegalEntityScope(UUID legalEntityId) {
        if (!authz.can(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE,
                new AuthorizationTarget.LegalEntity(legalEntityId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "legal-entity-not-in-scope",
                    "Changing holiday calendars of legal entity " + legalEntityId + " is outside your data scope");
        }
    }

    private void requireReplacedHoliday(UUID calendarId, DateEntry e) {
        if ("SUBSTITUTE".equals(e.dayType()) && !store.hasActivePublicDate(calendarId, e.substituteForDate())) {
            throw invalid("substitute-for-unknown", "There is no active public holiday on " + e.substituteForDate()
                    + " in this calendar for the substitute day to replace");
        }
    }

    private void requireNoSubstitutes(UUID calendarId, LocalDate holiday) {
        List<LocalDate> subs = store.activeSubstitutesOf(calendarId, holiday);
        if (!subs.isEmpty()) {
            throw ApiException.conflict("holiday-has-substitutes", "The holiday on " + holiday
                    + " is replaced by substitute day(s) " + subs + "; change or cancel those first");
        }
    }

    private static String calendarCode(String code) {
        String c = HolidayDateRules.upper(code);
        if (c == null || !HolidayDateRules.CALENDAR_CODE.matcher(c).matches()) {
            throw invalid("invalid-calendar-code", "The calendar code is 1-40 upper-case letters, digits, '_' or '-'");
        }
        return c;
    }

    private static String name(String nameVi) {
        if (nameVi == null || nameVi.isBlank()) {
            throw invalid("calendar-name-required", "The Vietnamese name is required");
        }
        return nameVi.strip();
    }

    private static int year(int year) {
        if (year < 2000 || year > 2100) {
            throw invalid("invalid-year", "The year must be between 2000 and 2100");
        }
        return year;
    }

    private static ApiException stale(String what) {
        return ApiException.conflict("stale-version", "The " + what + " was changed by someone else; reload it");
    }
}
