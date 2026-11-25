package vn.yourhrm.attendance.internal.web.timesheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
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
import vn.yourhrm.attendance.internal.application.timesheet.AttendanceCodeService;
import vn.yourhrm.attendance.internal.application.timesheet.AttendanceCodeService.CodeInput;
import vn.yourhrm.attendance.internal.application.timesheet.CorrectionService;
import vn.yourhrm.attendance.internal.application.timesheet.CorrectionService.EditInput;
import vn.yourhrm.attendance.internal.application.timesheet.CorrectionService.EditItem;
import vn.yourhrm.attendance.internal.application.timesheet.CorrectionService.EditResult;
import vn.yourhrm.attendance.internal.application.timesheet.CorrectionService.RequestInput;
import vn.yourhrm.attendance.internal.application.timesheet.TimesheetProcessor.Summary;
import vn.yourhrm.attendance.internal.application.timesheet.TimesheetQueries;
import vn.yourhrm.attendance.internal.application.timesheet.TimesheetQueries.DayDetail;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CodeRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CorrectionRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.DayRow;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Daily timesheets (BRD 03 §4.4: BR-ATT-030..039): the attendance code catalogue, the grid and the day drill-down,
 * recalculation, admin / bulk edits with a reason and correction requests through Core's workflow. A computed day is
 * never edited directly: edits and approved requests are inputs that every recalculation applies.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class TimesheetController {

    private final AttendanceCodeService codes;
    private final TimesheetQueries timesheets;
    private final CorrectionService corrections;

    TimesheetController(AttendanceCodeService codes, TimesheetQueries timesheets, CorrectionService corrections) {
        this.codes = codes;
        this.timesheets = timesheets;
        this.corrections = corrections;
    }

    record CodeRequest(String code, String labelVi, String labelEn, String kind, String payrollCategory,
            String payrollSubCode, Boolean countsAsWorked, String paidBy, Boolean siNonwork, Integer displayOrder,
            String colorHex, String status, Integer version) {

        CodeInput input() {
            return new CodeInput(code, labelVi, labelEn, kind, payrollCategory, payrollSubCode, countsAsWorked, paidBy,
                    siNonwork, displayOrder, colorHex, status);
        }
    }

    record RecomputeRequest(UUID legalEntityId, List<UUID> employmentIds, @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }

    record EditRequest(List<EditItem> days, String code, Instant inAt, Instant outAt, String reason) {
    }

    record CorrectionRequest(@NotNull LocalDate workDate, @NotNull String correctionType, Instant requestedIn,
            Instant requestedOut, String requestedCode, String reasonCode, String reasonText,
            List<UUID> attachmentDocumentIds) {
    }

    // -------------------------------------------------------------------------------------------- codes

    @GetMapping("/attendance-codes")
    @RequiresPermission(AttendancePermissions.ATTENDANCE_CODE_VIEW)
    public List<CodeRow> codes() {
        return codes.list();
    }

    @PostMapping("/attendance-codes")
    @RequiresPermission(AttendancePermissions.ATTENDANCE_CODE_UPDATE)
    public ResponseEntity<CodeRow> createCode(@RequestBody CodeRequest req) {
        return ResponseEntity.status(201).body(codes.create(req.input()));
    }

    @PutMapping("/attendance-codes/{id}")
    @RequiresPermission(AttendancePermissions.ATTENDANCE_CODE_UPDATE)
    public CodeRow updateCode(@PathVariable UUID id, @RequestBody CodeRequest req) {
        return codes.update(id, req.version() == null ? -1 : req.version(), req.input());
    }

    // ----------------------------------------------------------------------------------------- timesheets

    /** The grid of a legal entity / org unit (US-ATT-032), filtered by status, exception or blocking exceptions. */
    @GetMapping("/timesheets")
    @RequiresPermission(AttendancePermissions.TIMESHEET_VIEW)
    public List<DayRow> grid(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String exception,
            @RequestParam(defaultValue = "false") boolean blockingOnly,
            @RequestParam(defaultValue = "1000") int limit, @RequestParam(defaultValue = "0") int offset) {
        return timesheets.grid(legalEntityId, orgUnitId, from, to, status, exception, blockingOnly, limit, offset);
    }

    @GetMapping("/employments/{employmentId}/timesheets")
    @RequiresPermission(AttendancePermissions.TIMESHEET_VIEW)
    public List<DayRow> days(@PathVariable UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return timesheets.ofEmployment(employmentId, from, to);
    }

    /** One day with its raw punches (audited read) and its corrections. */
    @GetMapping("/employments/{employmentId}/timesheets/{workDate}")
    @RequiresPermission(AttendancePermissions.TIMESHEET_VIEW)
    public DayDetail day(@PathVariable UUID employmentId,
            @PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate workDate) {
        return timesheets.day(employmentId, workDate);
    }

    @PostMapping("/timesheets/recompute")
    @RequiresPermission(AttendancePermissions.TIMESHEET_UPDATE)
    public Summary recompute(@Valid @RequestBody RecomputeRequest req) {
        return timesheets.recompute(req.legalEntityId(), req.employmentIds(), req.from(), req.to());
    }

    /** Admin edit of one day or bulk edit of many, with a mandatory reason (BR-ATT-037). */
    @PostMapping("/timesheets/edits")
    @RequiresPermission(AttendancePermissions.TIMESHEET_UPDATE)
    public EditResult edit(@RequestBody EditRequest req) {
        return corrections.edit(new EditInput(req.days(), req.code(), req.inAt(), req.outAt(), req.reason()));
    }

    /** A correction request of one day, approved through Core's workflow (BR-ATT-036). */
    @PostMapping("/employments/{employmentId}/timesheet-corrections")
    @RequiresPermission(AttendancePermissions.TIMESHEET_CORRECTION_REQUEST)
    public ResponseEntity<CorrectionRow> request(@PathVariable UUID employmentId,
            @Valid @RequestBody CorrectionRequest req) {
        return ResponseEntity.status(201).body(corrections.request(employmentId, new RequestInput(req.workDate(),
                req.correctionType(), req.requestedIn(), req.requestedOut(), req.requestedCode(), req.reasonCode(),
                req.reasonText(), req.attachmentDocumentIds())));
    }
}
