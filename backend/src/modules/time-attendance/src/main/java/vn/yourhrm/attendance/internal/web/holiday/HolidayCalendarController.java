package vn.yourhrm.attendance.internal.web.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.HolidayCalendars;
import vn.yourhrm.attendance.api.HolidayCalendars.Holiday;
import vn.yourhrm.attendance.internal.application.holiday.HolidayCalendarService;
import vn.yourhrm.attendance.internal.application.holiday.HolidayCalendarService.ApplyResult;
import vn.yourhrm.attendance.internal.application.holiday.HolidayCalendarService.AssignmentView;
import vn.yourhrm.attendance.internal.application.holiday.HolidayCalendarService.CalendarView;
import vn.yourhrm.attendance.internal.application.holiday.HolidayCalendarService.DiffItem;
import vn.yourhrm.attendance.internal.application.holiday.HolidayCalendarService.HolidayDateView;
import vn.yourhrm.attendance.internal.application.holiday.HolidayCalendarService.TemplateView;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.DateEntry;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Holiday calendars (BRD 03 §4.1: BR-ATT-001/002/005/006; US-ATT-001/002/005/006/007). Dates are ISO
 * {@code yyyy-MM-dd}; assignment end dates are exclusive.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class HolidayCalendarController {

    private final HolidayCalendarService calendars;
    private final HolidayCalendars resolved;

    HolidayCalendarController(HolidayCalendarService calendars, HolidayCalendars resolved) {
        this.calendars = calendars;
        this.resolved = resolved;
    }

    /** A CUSTOM calendar, or (with {@code templateId}) a copy of a platform template, filled for {@code year}. */
    record CreateCalendarRequest(@Size(max = 40) @NotBlank String code, @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn, UUID templateId, Integer year) {
    }

    record UpdateCalendarRequest(@Size(max = 200) String nameVi, @Size(max = 200) String nameEn, String status,
            @NotNull Integer version) {
    }

    record DateRequest(
            @NotNull LocalDate holidayDate,
            @NotBlank String dayType,
            @Size(max = 40) String holidayCode,
            @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn,
            Integer lunarMonth,
            Integer lunarDay,
            Boolean lunarLeapMonth,
            LocalDate substituteForDate,
            Boolean paid,
            String eligibility,
            @Size(min = 2, max = 2) String nationalityCode,
            @Size(max = 500) String legalRef,
            Integer version,
            @Size(max = 500) String reason) {

        DateEntry toEntry() {
            return new DateEntry(holidayDate, dayType, holidayCode, nameVi, nameEn, lunarMonth, lunarDay,
                    Boolean.TRUE.equals(lunarLeapMonth), substituteForDate, paid == null || paid, eligibility,
                    nationalityCode, legalRef);
        }
    }

    record CancelRequest(@NotNull Integer version, @NotBlank @Size(max = 500) String reason) {
    }

    record ApplyTemplateRequest(@NotNull Integer year, List<String> acceptKeys, @Size(max = 500) String reason) {
    }

    record AssignRequest(@NotNull UUID legalEntityId, UUID locationId, @NotNull LocalDate validFrom,
            LocalDate validTo) {
    }

    record EndAssignmentRequest(LocalDate validTo, @NotNull Integer version) {
    }

    // ------------------------------------------------------------------------------------------- calendars

    @GetMapping("/holiday-calendars")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_VIEW)
    public List<CalendarView> list() {
        return calendars.list();
    }

    @PostMapping("/holiday-calendars")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE)
    public ResponseEntity<CalendarView> create(@Valid @RequestBody CreateCalendarRequest req) {
        CalendarView created = req.templateId() == null
                ? calendars.create(req.code(), req.nameVi(), req.nameEn())
                : calendars.createFromTemplate(req.templateId(), req.code(), req.nameVi(), req.nameEn(), req.year());
        return ResponseEntity.created(URI.create("/api/v1/attendance/holiday-calendars/" + created.id()))
                .body(created);
    }

    @GetMapping("/holiday-calendars/{id}")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_VIEW)
    public CalendarView get(@PathVariable UUID id) {
        return calendars.get(id);
    }

    @PutMapping("/holiday-calendars/{id}")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE)
    public CalendarView update(@PathVariable UUID id, @Valid @RequestBody UpdateCalendarRequest req) {
        return calendars.update(id, req.nameVi(), req.nameEn(), req.status(), req.version());
    }

    // ------------------------------------------------------------------------------------------- dates

    @GetMapping("/holiday-calendars/{id}/dates")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_VIEW)
    public List<HolidayDateView> dates(@PathVariable UUID id, @RequestParam int year,
            @RequestParam(defaultValue = "false") boolean includeCancelled) {
        return calendars.dates(id, year, includeCancelled);
    }

    @PostMapping("/holiday-calendars/{id}/dates")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE)
    public ResponseEntity<HolidayDateView> addDate(@PathVariable UUID id, @Valid @RequestBody DateRequest req) {
        HolidayDateView created = calendars.addDate(id, req.toEntry());
        return ResponseEntity.created(URI.create("/api/v1/attendance/holiday-calendars/" + id + "/dates/"
                + created.id())).body(created);
    }

    /** Replaces a date (template override); {@code version} and {@code reason} are required. */
    @PutMapping("/holiday-calendars/{id}/dates/{dateId}")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE)
    public HolidayDateView updateDate(@PathVariable UUID id, @PathVariable UUID dateId,
            @Valid @RequestBody DateRequest req) {
        if (req.version() == null) {
            throw vn.yourhrm.shared.common.error.ApiException.unprocessable("version-required",
                    "The version read is required to update");
        }
        return calendars.updateDate(id, dateId, req.toEntry(), req.version(), req.reason());
    }

    @PostMapping("/holiday-calendars/{id}/dates/{dateId}/cancel")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE)
    public HolidayDateView cancelDate(@PathVariable UUID id, @PathVariable UUID dateId,
            @Valid @RequestBody CancelRequest req) {
        return calendars.cancelDate(id, dateId, req.version(), req.reason());
    }

    // ------------------------------------------------------------------------------------------- templates

    @GetMapping("/holiday-calendar-templates")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_VIEW)
    public List<TemplateView> templates() {
        return calendars.templates();
    }

    @GetMapping("/holiday-calendar-templates/{templateId}/dates")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_VIEW)
    public List<HolidayDateView> templateDates(@PathVariable UUID templateId, @RequestParam int year) {
        return calendars.templateDates(templateId, year);
    }

    @GetMapping("/holiday-calendars/{id}/template-diff")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_VIEW)
    public List<DiffItem> templateDiff(@PathVariable UUID id, @RequestParam int year) {
        return calendars.templateDiff(id, year);
    }

    /** Without {@code acceptKeys}: every ADDED / REMOVED difference; CHANGED and OVERRIDDEN ones only when named. */
    @PostMapping("/holiday-calendars/{id}/template-apply")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE)
    public ApplyResult applyTemplate(@PathVariable UUID id, @Valid @RequestBody ApplyTemplateRequest req) {
        return calendars.applyTemplate(id, req.year(), req.acceptKeys(), req.reason());
    }

    // ------------------------------------------------------------------------------------------- assignments

    @PostMapping("/holiday-calendars/{id}/assignments")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE)
    public ResponseEntity<AssignmentView> assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest req) {
        AssignmentView created = calendars.assign(id, req.legalEntityId(), req.locationId(), req.validFrom(),
                req.validTo());
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/holiday-calendars/{id}/assignments/{assignmentId}")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE)
    public AssignmentView endAssignment(@PathVariable UUID id, @PathVariable UUID assignmentId,
            @Valid @RequestBody EndAssignmentRequest req) {
        return calendars.endAssignment(id, assignmentId, req.validTo(), req.version());
    }

    @GetMapping("/legal-entities/{legalEntityId}/holiday-calendar-assignments")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_VIEW)
    public List<AssignmentView> assignmentsOfLegalEntity(@PathVariable UUID legalEntityId) {
        return calendars.assignmentsOfLegalEntity(legalEntityId);
    }

    /** The resolved holidays of a legal entity / location (ATT-P-16 over HTTP), e.g. for the ESS calendar. */
    @GetMapping("/legal-entities/{legalEntityId}/holidays")
    @RequiresPermission(AttendancePermissions.HOLIDAY_CALENDAR_VIEW)
    public List<Holiday> holidays(@PathVariable UUID legalEntityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String nationality) {
        return resolved.holidays(legalEntityId, locationId, from, to, nationality);
    }
}
