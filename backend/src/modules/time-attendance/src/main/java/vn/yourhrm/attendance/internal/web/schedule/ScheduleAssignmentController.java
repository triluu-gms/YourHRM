package vn.yourhrm.attendance.internal.web.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.WorkSchedules.PlannedDay;
import vn.yourhrm.attendance.internal.application.schedule.ScheduleAssignmentService;
import vn.yourhrm.attendance.internal.application.schedule.ScheduleAssignmentService.ScheduleAssignmentView;
import vn.yourhrm.attendance.internal.application.schedule.ScheduleAssignmentService.Unscheduled;
import vn.yourhrm.attendance.internal.domain.schedule.StandardDays;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Schedule assignment per employment (BR-ATT-015/016, US-ATT-010/011/012): list, manual override, cancel,
 * regenerate, planned days, standard days, and the "no schedule" list of a legal entity.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class ScheduleAssignmentController {

    private final ScheduleAssignmentService assignments;

    ScheduleAssignmentController(ScheduleAssignmentService assignments) {
        this.assignments = assignments;
    }

    record ManualRequest(@NotNull UUID assignmentKey, @NotNull UUID patternId, @NotNull LocalDate validFrom,
            LocalDate validTo) {
    }

    @GetMapping("/employments/{employmentId}/schedule-assignments")
    @RequiresPermission(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW)
    public List<ScheduleAssignmentView> list(@PathVariable UUID employmentId,
            @RequestParam(defaultValue = "false") boolean includeCancelled) {
        return assignments.list(employmentId, includeCancelled);
    }

    @PostMapping("/employments/{employmentId}/schedule-assignments")
    @RequiresPermission(AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE)
    public ResponseEntity<ScheduleAssignmentView> assignManual(@PathVariable UUID employmentId,
            @Valid @RequestBody ManualRequest req) {
        return ResponseEntity.status(201).body(assignments.assignManual(employmentId, req.assignmentKey(),
                req.patternId(), req.validFrom(), req.validTo()));
    }

    @PostMapping("/employments/{employmentId}/schedule-assignments/{id}/cancel")
    @RequiresPermission(AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE)
    public ScheduleAssignmentView cancel(@PathVariable UUID employmentId, @PathVariable UUID id) {
        return assignments.cancel(employmentId, id);
    }

    @PostMapping("/employments/{employmentId}/schedule-assignments/regenerate")
    @RequiresPermission(AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE)
    public List<ScheduleAssignmentView> regenerate(@PathVariable UUID employmentId) {
        return assignments.regenerate(employmentId);
    }

    /** Planned days: pattern day, shift, holiday and per-employee substitute days (BR-ATT-003/014). */
    @GetMapping("/employments/{employmentId}/work-plan")
    @RequiresPermission(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW)
    public List<PlannedDay> plan(@PathVariable UUID employmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return assignments.plan(employmentId, from, to);
    }

    /** STD_DAYS / STD_HOURS of an attendance period, with the method and value Payroll published for it. */
    @GetMapping("/employments/{employmentId}/standard-days")
    @RequiresPermission(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW)
    public StandardDays.Result standardDays(@PathVariable UUID employmentId, @RequestParam UUID periodId) {
        return assignments.standardDays(employmentId, periodId);
    }

    /**
     * Preview for any {@code [from, to]} with a {@code method} (FIXED_DAYS, SCHEDULE_DAYS, CALENDAR_DAYS,
     * FIXED_HOURS) and its {@code value} for the fixed methods, e.g. before the pay group's first period exists.
     */
    @GetMapping("/employments/{employmentId}/standard-days/preview")
    @RequiresPermission(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW)
    public StandardDays.Result standardDaysPreview(@PathVariable UUID employmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String method, @RequestParam(required = false) BigDecimal value) {
        return assignments.standardDays(employmentId, from, to, method, value);
    }

    /** Assignment lines valid on {@code date} without a schedule (US-ATT-011 exception list). */
    @GetMapping("/legal-entities/{legalEntityId}/unscheduled-employees")
    @RequiresPermission(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW)
    public List<Unscheduled> unscheduled(@PathVariable UUID legalEntityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return assignments.unscheduled(legalEntityId, date);
    }
}
