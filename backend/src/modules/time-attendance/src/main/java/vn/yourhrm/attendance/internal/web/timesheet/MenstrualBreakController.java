package vn.yourhrm.attendance.internal.web.timesheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.timesheet.MenstrualBreakService;
import vn.yourhrm.attendance.internal.application.timesheet.MenstrualBreakService.Declaration;
import vn.yourhrm.attendance.internal.application.timesheet.MenstrualBreakService.DeclareResult;
import vn.yourhrm.attendance.internal.application.timesheet.MenstrualBreakService.MonthView;
import vn.yourhrm.shared.auth.api.PublicEndpoint;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Days of the paid menstrual break (BR-ATT-035, VN-16): declared by the worker (ESS, SELF scope) or by HR on her
 * behalf, without approval; each declaration recomputes the day. The body carries the date only (no reason).
 */
@RestController
@RequestMapping("/api/v1/attendance")
class MenstrualBreakController {

    private final MenstrualBreakService service;

    MenstrualBreakController(MenstrualBreakService service) {
        this.service = service;
    }

    record DeclareRequest(@NotNull LocalDate workDate) {
    }

    /**
     * Declarations of a month ({@code month=2026-11}, default this month). Authorised in the service: the worker
     * herself through {@code att.menstrual_break.declare}, anybody else through {@code att.menstrual_break.view}
     * (audited), so the endpoint accepts either permission.
     */
    @GetMapping("/employments/{employmentId}/menstrual-breaks")
    @PublicEndpoint(reason = "either att.menstrual_break.declare (own days) or att.menstrual_break.view, checked per"
            + " employment in MenstrualBreakService.month")
    public MonthView month(@PathVariable UUID employmentId, @RequestParam(required = false) String month) {
        YearMonth m;
        try {
            m = month == null || month.isBlank() ? null : YearMonth.parse(month.strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw vn.yourhrm.shared.common.error.ApiException.unprocessable("invalid-month", "The month is YYYY-MM");
        }
        return service.month(employmentId, m);
    }

    @PostMapping("/employments/{employmentId}/menstrual-breaks")
    @RequiresPermission(AttendancePermissions.MENSTRUAL_BREAK_DECLARE)
    public ResponseEntity<DeclareResult> declare(@PathVariable UUID employmentId,
            @Valid @RequestBody DeclareRequest req) {
        return ResponseEntity.status(201).body(service.declare(employmentId, req.workDate()));
    }

    @DeleteMapping("/employments/{employmentId}/menstrual-breaks/{workDate}")
    @RequiresPermission(AttendancePermissions.MENSTRUAL_BREAK_DECLARE)
    public Declaration withdraw(@PathVariable UUID employmentId,
            @PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate workDate) {
        return service.withdraw(employmentId, workDate);
    }
}
