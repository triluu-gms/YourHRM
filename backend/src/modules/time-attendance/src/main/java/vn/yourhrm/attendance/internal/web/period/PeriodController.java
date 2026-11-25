package vn.yourhrm.attendance.internal.web.period;

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
import vn.yourhrm.attendance.api.AttendanceResults.Hold;
import vn.yourhrm.attendance.api.AttendanceResults.Line;
import vn.yourhrm.attendance.api.AttendanceResults.Result;
import vn.yourhrm.attendance.internal.application.period.PeriodService;
import vn.yourhrm.attendance.internal.application.period.PeriodService.Checklist;
import vn.yourhrm.attendance.internal.application.period.PeriodService.PeriodView;
import vn.yourhrm.attendance.internal.application.period.PeriodService.ReopenOutcome;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.RetroCandidate;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Attendance periods and their close (BRD §4.8): list and detail, the pre-close checklist, review, close / re-close,
 * reopen (or a release request when Payroll holds the period), a leaver's early close, the published versions with
 * their lines, the holds and the pending retro requests. Payroll itself uses the in-process API
 * {@code AttendanceResults} (ATT-P-17), not these endpoints.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class PeriodController {

    private final PeriodService periods;

    PeriodController(PeriodService periods) {
        this.periods = periods;
    }

    /** @param waive checklist codes waived explicitly; {@code waiveReason} is then required (BR-ATT-074) */
    record CloseRequest(List<String> waive, String waiveReason) {
    }

    record ReopenRequest(String reason) {
    }

    @GetMapping("/attendance-periods")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public List<PeriodView> list(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID payGroupId, @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return periods.list(legalEntityId, payGroupId, status, from, to);
    }

    @GetMapping("/attendance-periods/{id}")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public PeriodView get(@PathVariable UUID id) {
        return periods.get(id);
    }

    @GetMapping("/attendance-periods/{id}/checklist")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public Checklist checklist(@PathVariable UUID id, @RequestParam(required = false) UUID employmentId) {
        return periods.checklist(id, employmentId);
    }

    @PostMapping("/attendance-periods/{id}/review")
    @RequiresPermission(AttendancePermissions.PERIOD_CLOSE)
    public PeriodView review(@PathVariable UUID id) {
        return periods.review(id);
    }

    /** 422 {@code close-blocked} with the checklist when something blocks; 201 with the new version. */
    @PostMapping("/attendance-periods/{id}/close")
    @RequiresPermission(AttendancePermissions.PERIOD_CLOSE)
    public ResponseEntity<Result> close(@PathVariable UUID id, @RequestBody(required = false) CloseRequest r) {
        return ResponseEntity.status(201).body(periods.close(id, r == null ? null : r.waive(),
                r == null ? null : r.waiveReason()));
    }

    /** 200 REOPENED, or 202 RELEASE_REQUESTED when Payroll holds the period (BR-ATT-087). */
    @PostMapping("/attendance-periods/{id}/reopen")
    @RequiresPermission(AttendancePermissions.PERIOD_REOPEN)
    public ResponseEntity<ReopenOutcome> reopen(@PathVariable UUID id, @RequestBody(required = false) ReopenRequest r) {
        ReopenOutcome o = periods.reopen(id, r == null ? null : r.reason());
        return ResponseEntity.status("REOPENED".equals(o.outcome()) ? 200 : 202).body(o);
    }

    @PostMapping("/attendance-periods/{id}/employments/{employmentId}/early-close")
    @RequiresPermission(AttendancePermissions.PERIOD_CLOSE)
    public ResponseEntity<Result> earlyClose(@PathVariable UUID id, @PathVariable UUID employmentId,
            @RequestBody(required = false) CloseRequest r) {
        return ResponseEntity.status(201).body(periods.earlyClose(id, employmentId, r == null ? null : r.waive(),
                r == null ? null : r.waiveReason()));
    }

    @GetMapping("/attendance-periods/{id}/results")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public List<Result> results(@PathVariable UUID id) {
        return periods.results(id);
    }

    @GetMapping("/attendance-periods/{id}/holds")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public List<Hold> holds(@PathVariable UUID id) {
        return periods.holds(id);
    }

    /** Employments whose days in this LOCKED period changed after the lock, waiting for the next close. */
    @GetMapping("/attendance-periods/{id}/retro-requests")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public List<RetroCandidate> retroRequests(@PathVariable UUID id) {
        return periods.pendingRetro(id);
    }

    @GetMapping("/attendance-results/{id}")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public Result result(@PathVariable UUID id) {
        return periods.result(id);
    }

    @GetMapping("/attendance-results/{id}/lines")
    @RequiresPermission(AttendancePermissions.PERIOD_VIEW)
    public List<Line> lines(@PathVariable UUID id, @RequestParam(required = false) UUID employmentId) {
        return periods.lines(id, employmentId);
    }
}
