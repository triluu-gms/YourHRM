package vn.yourhrm.attendance.internal.web.notify;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.AttendanceReminderJobs.Summary;
import vn.yourhrm.attendance.internal.application.notify.ReminderService;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Runs T&amp;A's daily reminders by hand (BR-ATT-082) until the platform job scheduler calls
 * {@code AttendanceReminderJobs}. {@code today} defaults to the business date.
 */
@RestController
@RequestMapping("/api/v1/attendance/reminders")
class ReminderController {

    private final ReminderService reminders;
    private final BusinessClock clock;

    ReminderController(ReminderService reminders, BusinessClock clock) {
        this.reminders = reminders;
        this.clock = clock;
    }

    record RunRequest(LocalDate today) {
    }

    @PostMapping("/run")
    @RequiresPermission(AttendancePermissions.REMINDER_RUN)
    public Summary run(@RequestBody(required = false) RunRequest req) {
        return reminders.runInScope(req == null || req.today() == null ? clock.today() : req.today());
    }
}
