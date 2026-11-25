package vn.yourhrm.attendance.api;

/**
 * Permission codes owned by Time &amp; Attendance (registered through T&amp;A's {@code PermissionCatalogue}; owner
 * module {@code att}, so every code starts with {@code att.}).
 */
public final class AttendancePermissions {

    private AttendancePermissions() {
    }

    /** Holiday calendars, their dates and their legal-entity assignments; the platform VN templates (BR-ATT-001/002). */
    public static final String HOLIDAY_CALENDAR_VIEW = "att.holiday_calendar.view";

    /**
     * Create and change holiday calendars, their dates and assignments; accept or override the VN template. A change
     * of a calendar also needs the permission's scope on every legal entity the calendar is assigned to.
     */
    public static final String HOLIDAY_CALENDAR_UPDATE = "att.holiday_calendar.update";

    /** Shift definitions, schedule patterns and schedule assignment rules (BR-ATT-010/011/014/015): configuration. */
    public static final String SCHEDULE_CONFIG_VIEW = "att.schedule_config.view";
    public static final String SCHEDULE_CONFIG_UPDATE = "att.schedule_config.update";

    /**
     * Schedule assignments of employees, their planned days and standard days (BR-ATT-015/016). Data-scoped per
     * employment (legal entity, org unit, location, reports).
     */
    public static final String SCHEDULE_ASSIGNMENT_VIEW = "att.schedule_assignment.view";
    public static final String SCHEDULE_ASSIGNMENT_UPDATE = "att.schedule_assignment.update";

    /**
     * Device registry (terminal codes found in device-export files) and enrolment mappings, device user id ↔
     * employment (BR-ATT-022). Data-scoped by the device's legal entity / location (mappings: also the employment).
     */
    public static final String DEVICE_VIEW = "att.device.view";
    public static final String DEVICE_UPDATE = "att.device.update";

    /** Raw punches of employees (BR-ATT-020, pii:P2 presence data): every read is audited (NFR-ATT-008/US-ATT-021). */
    public static final String PUNCH_VIEW = "att.punch.view";

    /** Upload punch files through Core's import engine: standard Excel template and device-export layouts (BR-ATT-025). */
    public static final String PUNCH_IMPORT = "att.punch.import";

    /** The quarantine of unmatched punches: re-match after a mapping change, or ignore with a reason (BR-ATT-022). */
    public static final String PUNCH_RESOLVE = "att.punch.resolve";

    /** Daily timesheets: the grid, the day detail with its punches and corrections (BR-ATT-032/033, US-ATT-032). */
    public static final String TIMESHEET_VIEW = "att.timesheet.view";

    /** Admin / bulk edit of days with a mandatory reason, and recalculation of a range (BR-ATT-037/038). */
    public static final String TIMESHEET_UPDATE = "att.timesheet.update";

    /**
     * Request a correction of one's own day through Core's workflow (BR-ATT-036): granted with SELF scope to employees
     * (ESS), or wider to HR submitting on behalf.
     */
    public static final String TIMESHEET_CORRECTION_REQUEST = "att.timesheet_correction.request";

    /** The attendance code catalogue: platform defaults and tenant codes with their payroll category (BR-ATT-033). */
    public static final String ATTENDANCE_CODE_VIEW = "att.attendance_code.view";
    public static final String ATTENDANCE_CODE_UPDATE = "att.attendance_code.update";

    /** Overtime requests, results and cap usage of employees (BR-ATT-040..046). Data-scoped per employment. */
    public static final String OVERTIME_VIEW = "att.overtime.view";

    /**
     * Create, submit and cancel overtime requests for employments in scope (BR-ATT-040): a manager for the team, or
     * an employee for themself with SELF scope.
     */
    public static final String OVERTIME_REQUEST = "att.overtime.request";

    /** Give the employee's own overtime consent (BLLĐ Art. 107(2)): granted with SELF scope to employees (ESS). */
    public static final String OVERTIME_CONSENT = "att.overtime.consent";

    /** Submit overtime above the monthly / annual cap with a reason (authorised override, BR-ATT-046). */
    public static final String OVERTIME_CAP_OVERRIDE = "att.overtime.cap_override";

    /** Leave types and their effective-dated policies (BR-ATT-055..060): configuration. */
    public static final String LEAVE_TYPE_VIEW = "att.leave_type.view";
    public static final String LEAVE_TYPE_UPDATE = "att.leave_type.update";

    /** Leave requests of employments in scope (team view, HR list), without the SI claim data. */
    public static final String LEAVE_REQUEST_VIEW = "att.leave_request.view";

    /**
     * Create and withdraw leave requests (BR-ATT-061/062): granted with SELF scope to employees (ESS), wider to HR or
     * managers submitting on behalf.
     */
    public static final String LEAVE_REQUEST_CREATE = "att.leave_request.create";

    /** HR handling of approved requests: SI claim data after the fact, maternity early return (BR-ATT-063/065). */
    public static final String LEAVE_REQUEST_MANAGE = "att.leave_request.manage";

    /** Read the SI claim data of a request (P3 health data, ND13/2023): every read is audited (BR-ATT-063). */
    public static final String LEAVE_SI_DATA_VIEW = "att.leave_request.view_si_data";

    /** Leave balances and the ledger of employments in scope (BR-ATT-068). */
    public static final String LEAVE_BALANCE_VIEW = "att.leave_balance.view";

    /**
     * Balance movements by HR: adjustments with a reason, accrual and year-end runs, carried-day expiry and the
     * termination settlement (BR-ATT-058/060/067/068).
     */
    public static final String LEAVE_BALANCE_ADJUST = "att.leave_balance.adjust";

    /** Load opening leave balances at go-live through Core's import engine ({@code OB_LEAVE_BALANCE}, ADR-0037). */
    public static final String LEAVE_BALANCE_IMPORT = "att.leave_balance.import";

    /**
     * Attendance periods, their pre-close checklist, the published Attendance Result versions with their lines, the
     * close holds and retro requests (BR-ATT-071..076, 079). Data-scoped by the period's legal entity.
     */
    public static final String PERIOD_VIEW = "att.period.view";

    /** Move a period to review, close / re-close it (waiving exceptions with a reason), early-close a leaver. */
    public static final String PERIOD_CLOSE = "att.period.close";

    /**
     * Reopen a CLOSED period with a reason (T&amp;A admin, BR-ATT-073); on a held period this files a release request
     * to Payroll and the reopen runs when the hold is released (BR-ATT-087).
     */
    public static final String PERIOD_REOPEN = "att.period.reopen";

    /**
     * Payroll side of the close hold: receives the release requests of held periods (BR-ATT-087). Granted to payroll
     * administrators with their legal-entity scope.
     */
    public static final String PERIOD_HOLD = "att.period.hold";

    /**
     * Upload year-to-date overtime at go-live through Core's import engine ({@code OB_OT_YTD}, BR-ATT-086): the
     * opening minutes of the monthly / annual cap ledger.
     */
    public static final String OVERTIME_IMPORT = "att.overtime.import";

    /**
     * Employee self-service (BR-ATT-080): the caller's own schedule, punches, daily timesheets with their exceptions,
     * leave balances with the ledger, overtime cap usage and the status of their requests. Only its SELF scope is
     * used (the user ↔ employment link of Core): a wider scope never shows anybody else's data.
     */
    public static final String ESS_VIEW = "att.ess.view";

    /**
     * Run T&amp;A's daily reminders by hand (BR-ATT-082: missing punches, carried leave about to expire, close
     * deadlines) until the platform job scheduler runs {@code AttendanceReminderJobs}. Tenant-wide or per legal entity.
     */
    public static final String REMINDER_RUN = "att.reminder.run";

    /** Operations: T&A's inbox of events from other modules, incl. failed ones (dead letters). */
    public static final String INTEGRATION_VIEW = "att.integration.view";

    /** Operations: rebuild a projection from its owner's API (Core's locations), tenant-wide. */
    public static final String INTEGRATION_RESYNC = "att.integration.resync";

    /**
     * Import whole daily timesheets through Core's import engine ({@code ATT_TIMESHEET_DAY}, BR-ATT-025, US-ATT-018)
     * for sites without a device: per employee and day a code and / or the in / out times, with a reason. Each row is a
     * correction of source IMPORT that daily processing applies on every recalculation (the day is never edited).
     */
    public static final String TIMESHEET_IMPORT = "att.timesheet.import";

    /**
     * Import the Attendance Result of a period ({@code ATT_RESULT_IMPORT}, BR-ATT-025 / 076, R2-18, US-ATT-019):
     * quantities per employee and category become a standard CLOSED result version for Payroll. Always-on baseline
     * capability (tenants with Payroll but without T&amp;A).
     */
    public static final String RESULT_IMPORT = "att.result.import";

    /** Run T&amp;A's nightly job by hand (partitions, review move, recalculation, leave runs, reconciliation). */
    public static final String JOB_RUN = "att.job.run";

    /**
     * Download the T&amp;A reports as Excel files (BRD 03 §8: R-01 timesheet grid, R-04 overtime summary, R-06 leave
     * balances); every export is audited. The rows are still limited to the caller's scope of the report's own view
     * permission ({@code att.timesheet.view}, {@code att.overtime.view}, {@code att.leave_balance.view}). The on-screen
     * reports need only that view permission.
     */
    public static final String REPORT_EXPORT = "att.report.export";
}
