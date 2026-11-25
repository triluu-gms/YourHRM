package vn.yourhrm.attendance.internal.web.report;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.report.LeaveReports;
import vn.yourhrm.attendance.internal.application.report.LeaveReports.BalanceReport;
import vn.yourhrm.attendance.internal.application.report.LeaveReports.SiLine;
import vn.yourhrm.attendance.internal.application.report.LeaveReports.TakenLine;
import vn.yourhrm.attendance.internal.application.report.OvertimeReports;
import vn.yourhrm.attendance.internal.application.report.OvertimeReports.OverrideLine;
import vn.yourhrm.attendance.internal.application.report.OvertimeReports.OvertimeSummary;
import vn.yourhrm.attendance.internal.application.report.PeriodReports;
import vn.yourhrm.attendance.internal.application.report.PeriodReports.CloseStatus;
import vn.yourhrm.attendance.internal.application.report.PeriodReports.RetroReport;
import vn.yourhrm.attendance.internal.application.report.ReportPage;
import vn.yourhrm.attendance.internal.application.report.TimesheetReports;
import vn.yourhrm.attendance.internal.application.report.TimesheetReports.ExceptionReport;
import vn.yourhrm.attendance.internal.application.report.TimesheetReports.GridQuery;
import vn.yourhrm.attendance.internal.application.report.TimesheetReports.GridReport;
import vn.yourhrm.attendance.internal.application.report.TimesheetReports.PunchLine;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * The T&amp;A reports of BRD 03 §8 (R-01..R-09, Must). JSON pages ({@code limit} ≤ 2000, {@code offset}); Excel for
 * R-01 / R-04 / R-06 with {@code att.report.export}. Rows are limited to the caller's data scope of each report's view
 * permission, like the list endpoints. {@code lang} = vi | en (default: the user's locale, else vi).
 */
@RestController
@RequestMapping("/api/v1/attendance/reports")
class AttendanceReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TimesheetReports timesheets;
    private final OvertimeReports overtime;
    private final LeaveReports leave;
    private final PeriodReports periods;

    AttendanceReportController(TimesheetReports timesheets, OvertimeReports overtime, LeaveReports leave,
            PeriodReports periods) {
        this.timesheets = timesheets;
        this.overtime = overtime;
        this.leave = leave;
        this.periods = periods;
    }

    private static ResponseEntity<byte[]> file(String name, byte[] body) {
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    // ------------------------------------------------------------------ R-01 timesheet grid (bảng chấm công)

    /** R-01: employee × day attendance codes with totals per payroll category (a period or ≤ 62 days). */
    @GetMapping("/timesheet-grid")
    @RequiresPermission(AttendancePermissions.TIMESHEET_VIEW)
    public GridReport timesheetGrid(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID periodId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return timesheets.grid(new GridQuery(legalEntityId, orgUnitId, periodId, from, to, lang), limit, offset);
    }

    /** R-01 Excel in the VN layout with the signature cells; {@code unitLabel} is printed as "Đơn vị". */
    @GetMapping("/timesheet-grid/export.xlsx")
    @RequiresPermission(AttendancePermissions.REPORT_EXPORT)
    public ResponseEntity<byte[]> timesheetGridExcel(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID periodId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang, @RequestParam(required = false) String unitLabel) {
        var x = timesheets.gridExcel(new GridQuery(legalEntityId, orgUnitId, periodId, from, to, lang), unitLabel);
        return file(x.fileName(), x.content());
    }

    // ---------------------------------------------------------------------------------- R-02 exceptions

    /** R-02: late, early, missing punch and absent days (≤ 62 days); {@code type} narrows to one kind. */
    @GetMapping("/daily-exceptions")
    @RequiresPermission(AttendancePermissions.TIMESHEET_VIEW)
    public ExceptionReport dailyExceptions(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String type, @RequestParam(required = false) String lang,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        return timesheets.exceptions(legalEntityId, orgUnitId, employmentId, from, to, type, lang, limit, offset);
    }

    // ---------------------------------------------------------------------------------- R-03 punch log

    /** R-03: raw punches by device / employee with source and validation status (audited read, ≤ 62 days). */
    @GetMapping("/punch-log")
    @RequiresPermission(AttendancePermissions.PUNCH_VIEW)
    public ReportPage<PunchLine> punchLog(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID employmentId, @RequestParam(required = false) String status,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return timesheets.punches(legalEntityId, orgUnitId, deviceId, employmentId, status, from, to, lang, limit,
                offset);
    }

    // ------------------------------------------------------------------------------- R-04 / R-05 overtime

    /** R-04: overtime per employee: month, year to date, % of the caps, hours per category. */
    @GetMapping("/overtime-summary")
    @RequiresPermission(AttendancePermissions.OVERTIME_VIEW)
    public OvertimeSummary overtimeSummary(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam YearMonth month,
            @RequestParam(required = false) String lang, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return overtime.summary(legalEntityId, orgUnitId, month, lang, limit, offset);
    }

    @GetMapping("/overtime-summary/export.xlsx")
    @RequiresPermission(AttendancePermissions.REPORT_EXPORT)
    public ResponseEntity<byte[]> overtimeSummaryExcel(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam YearMonth month,
            @RequestParam(required = false) String lang, @RequestParam(required = false) String unitLabel) {
        var x = overtime.summaryExcel(legalEntityId, orgUnitId, month, lang, unitLabel);
        return file(x.fileName(), x.content());
    }

    /** R-05: overtime submitted above the cap with an authorised override: who, why, cap usage (≤ 366 days). */
    @GetMapping("/overtime-cap-overrides")
    @RequiresPermission(AttendancePermissions.OVERTIME_VIEW)
    public ReportPage<OverrideLine> overtimeCapOverrides(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String lang,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        return overtime.capOverrides(legalEntityId, orgUnitId, employmentId, from, to, status, lang, limit, offset);
    }

    // ---------------------------------------------------------------------------------- R-06 / R-07 leave

    /** R-06: leave balances as of a date (default today) from the ledger. */
    @GetMapping("/leave-balances")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_VIEW)
    public BalanceReport leaveBalances(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID leaveTypeId,
            @RequestParam(required = false) UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String lang, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return leave.balances(legalEntityId, orgUnitId, leaveTypeId, employmentId, asOf, lang, limit, offset);
    }

    @GetMapping("/leave-balances/export.xlsx")
    @RequiresPermission(AttendancePermissions.REPORT_EXPORT)
    public ResponseEntity<byte[]> leaveBalancesExcel(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID leaveTypeId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String lang, @RequestParam(required = false) String unitLabel) {
        var x = leave.balancesExcel(legalEntityId, orgUnitId, leaveTypeId, asOf, lang, unitLabel);
        return file(x.fileName(), x.content());
    }

    /** R-07: approved leave per month × org unit × type (≤ 366 days, by start date). */
    @GetMapping("/leave-taken")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_VIEW)
    public ReportPage<TakenLine> leaveTaken(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID leaveTypeId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return leave.taken(legalEntityId, orgUnitId, leaveTypeId, from, to, lang, limit, offset);
    }

    /** R-07: register of SI-paid leave (sổ theo dõi nghỉ hưởng BHXH) with masked claim data; audited. */
    @GetMapping("/si-leave-register")
    @RequiresPermission(AttendancePermissions.LEAVE_SI_DATA_VIEW)
    public ReportPage<SiLine> siLeaveRegister(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID leaveTypeId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return leave.siRegister(legalEntityId, orgUnitId, leaveTypeId, from, to, lang, limit, offset);
    }

    // ------------------------------------------------------------------------------- R-08 / R-09 periods

    /** R-08: close status per legal entity × pay group with the outstanding checklist items. */
    @GetMapping("/period-close-status")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public ReportPage<CloseStatus> periodCloseStatus(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID payGroupId, @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return periods.closeStatus(legalEntityId, payGroupId, status, from, to, lang, limit, offset);
    }

    /** R-09: retro adjustment lines and totals by original period. */
    @GetMapping("/retro-adjustments")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public RetroReport retroAdjustments(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID payGroupId, @RequestParam(required = false) UUID originalPeriodId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID employmentId,
            @RequestParam(defaultValue = "false") boolean includeWithdrawn,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        return periods.retro(legalEntityId, payGroupId, originalPeriodId, from, to, employmentId, includeWithdrawn,
                limit, offset);
    }
}
