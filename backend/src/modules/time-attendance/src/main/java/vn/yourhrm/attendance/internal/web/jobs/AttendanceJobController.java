package vn.yourhrm.attendance.internal.web.jobs;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendanceJobs.NightlySummary;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.jobs.NightlyJobService;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Runs T&amp;A's nightly job for the caller's tenant by hand (operations, catch-up after an outage): partitions, the
 * period review move, the full recalculation, the leave runs and the hold reconciliation. {@code today} defaults to
 * the business date.
 */
@RestController
@RequestMapping("/api/v1/attendance/jobs")
class AttendanceJobController {

    private final NightlyJobService jobs;
    private final BusinessClock clock;

    AttendanceJobController(NightlyJobService jobs, BusinessClock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    record RunRequest(LocalDate today) {
    }

    @PostMapping("/nightly/run")
    @RequiresPermission(AttendancePermissions.JOB_RUN)
    public NightlySummary run(@RequestBody(required = false) RunRequest req) {
        return jobs.runInScope(req == null || req.today() == null ? clock.today() : req.today());
    }
}
