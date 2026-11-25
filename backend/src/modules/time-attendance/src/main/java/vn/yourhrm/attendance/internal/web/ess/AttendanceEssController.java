package vn.yourhrm.attendance.internal.web.ess;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.WorkSchedules.PlannedDay;
import vn.yourhrm.attendance.internal.application.ess.AttendanceEssService;
import vn.yourhrm.attendance.internal.application.ess.AttendanceEssService.MyBalance;
import vn.yourhrm.attendance.internal.application.ess.AttendanceEssService.MyDay;
import vn.yourhrm.attendance.internal.application.ess.AttendanceEssService.MyDayDetail;
import vn.yourhrm.attendance.internal.application.ess.AttendanceEssService.MyEmployment;
import vn.yourhrm.attendance.internal.application.ess.AttendanceEssService.MyLedgerEntry;
import vn.yourhrm.attendance.internal.application.ess.AttendanceEssService.MyPunch;
import vn.yourhrm.attendance.internal.application.ess.AttendanceEssService.MyRequest;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService.CapView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * T&amp;A pages of employee self-service (BR-ATT-080): read-only, the employee is always the caller. {@code lang}
 * ({@code vi} / {@code en}) overrides the user's locale for the labels; {@code employmentId} picks one of the
 * caller's own concurrent employments.
 */
@RestController
@RequestMapping("/api/v1/attendance/me")
class AttendanceEssController {

    private final AttendanceEssService ess;

    AttendanceEssController(AttendanceEssService ess) {
        this.ess = ess;
    }

    @GetMapping("/employments")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<MyEmployment> employments() {
        return ess.employments();
    }

    @GetMapping("/schedule")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<PlannedDay> schedule(@RequestParam(required = false) UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return ess.schedule(employmentId, from, to);
    }

    @GetMapping("/punches")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<MyPunch> punches(@RequestParam(required = false) UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return ess.punches(employmentId, from, to);
    }

    @GetMapping("/timesheets")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<MyDay> timesheets(@RequestParam(required = false) UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang) {
        return ess.timesheets(employmentId, from, to, lang);
    }

    @GetMapping("/timesheets/{date}")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public MyDayDetail day(@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID employmentId, @RequestParam(required = false) String lang) {
        return ess.day(employmentId, date, lang);
    }

    @GetMapping("/exceptions")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<MyDay> exceptions(@RequestParam(required = false) UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang) {
        return ess.exceptions(employmentId, from, to, lang);
    }

    @GetMapping("/leave-balances")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<MyBalance> balances(@RequestParam(required = false) UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String lang) {
        return ess.balances(employmentId, asOf, lang);
    }

    @GetMapping("/leave-ledger")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<MyLedgerEntry> ledger(@RequestParam(required = false) UUID employmentId,
            @RequestParam(required = false) UUID leaveTypeId, @RequestParam(required = false) Integer leaveYear,
            @RequestParam(required = false) String lang) {
        return ess.ledger(employmentId, leaveTypeId, leaveYear, lang);
    }

    @GetMapping("/overtime-caps")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<CapView> overtimeCaps(@RequestParam(required = false) UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return ess.overtimeCaps(employmentId, date);
    }

    @GetMapping("/requests")
    @RequiresPermission(AttendancePermissions.ESS_VIEW)
    public List<MyRequest> requests(@RequestParam(required = false) UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lang) {
        return ess.requests(employmentId, from, to, lang);
    }
}
