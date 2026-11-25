package vn.yourhrm.attendance.internal.web.overtime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
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
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService.CapView;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService.CreateCommand;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService.Outcome;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService.RegimeView;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeService.RequestView;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.ResultView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Pre-approved overtime (BR-ATT-040..046, 048, 049): requests (single or a manager's group request), the employee's
 * consent, submit with the legal checks, cancel; the paid results per category; cap usage and the annual regime.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class OvertimeController {

    private final OvertimeService overtime;

    OvertimeController(OvertimeService overtime) {
        this.overtime = overtime;
    }

    record CreateRequest(@NotEmpty List<UUID> employmentIds, @NotNull LocalDate workDate,
            @NotNull LocalTime startTime, @NotNull LocalTime endTime, String reason, Boolean consent,
            String consentMethod, Boolean submit, String capOverrideReason) {
    }

    record SubmitRequest(String capOverrideReason) {
    }

    record CancelRequest(String reason) {
    }

    /** One request per employment; with {@code submit} each is checked and, when nothing blocks, sent to approval. */
    @PostMapping("/overtime-requests")
    @RequiresPermission(AttendancePermissions.OVERTIME_REQUEST)
    public ResponseEntity<List<Outcome>> create(@Valid @RequestBody CreateRequest r) {
        return ResponseEntity.status(201).body(overtime.create(new CreateCommand(r.employmentIds(), r.workDate(),
                r.startTime(), r.endTime(), r.reason(), Boolean.TRUE.equals(r.consent()), r.consentMethod(),
                Boolean.TRUE.equals(r.submit()),
                r.capOverrideReason())));
    }

    @GetMapping("/overtime-requests/{id}")
    @RequiresPermission(AttendancePermissions.OVERTIME_VIEW)
    public RequestView get(@PathVariable UUID id) {
        return overtime.get(id);
    }

    @PostMapping("/overtime-requests/{id}/consent")
    @RequiresPermission(AttendancePermissions.OVERTIME_CONSENT)
    public RequestView consent(@PathVariable UUID id) {
        return overtime.consent(id);
    }

    /** 422 with the blocking finding's code and every finding when the request cannot be submitted. */
    @PostMapping("/overtime-requests/{id}/submit")
    @RequiresPermission(AttendancePermissions.OVERTIME_REQUEST)
    public Outcome submit(@PathVariable UUID id, @RequestBody(required = false) SubmitRequest r) {
        return overtime.submit(id, r == null ? null : r.capOverrideReason());
    }

    @PostMapping("/overtime-requests/{id}/cancel")
    @RequiresPermission(AttendancePermissions.OVERTIME_REQUEST)
    public RequestView cancel(@PathVariable UUID id, @RequestBody(required = false) CancelRequest r) {
        return overtime.cancel(id, r == null ? null : r.reason());
    }

    @GetMapping("/employments/{employmentId}/overtime-requests")
    @RequiresPermission(AttendancePermissions.OVERTIME_VIEW)
    public List<RequestView> list(@PathVariable UUID employmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return overtime.list(employmentId, from, to);
    }

    /** Paid overtime per work date and category (OT-WD … OT-PH-N) with approved and actual minutes. */
    @GetMapping("/employments/{employmentId}/overtime-results")
    @RequiresPermission(AttendancePermissions.OVERTIME_VIEW)
    public List<ResultView> results(@PathVariable UUID employmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return overtime.results(employmentId, from, to);
    }

    @GetMapping("/employments/{employmentId}/overtime-caps")
    @RequiresPermission(AttendancePermissions.OVERTIME_VIEW)
    public List<CapView> caps(@PathVariable UUID employmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return overtime.caps(employmentId, date);
    }

    /** Annual regime of the legal entity and whether the 300-hour notice must still be recorded (BR-ATT-049). */
    @GetMapping("/legal-entities/{legalEntityId}/overtime-regime")
    @RequiresPermission(AttendancePermissions.OVERTIME_VIEW)
    public RegimeView regime(@PathVariable UUID legalEntityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return overtime.regime(legalEntityId, date);
    }
}
