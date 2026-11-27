import type { MenuEntry } from '@/shared/navigation';
import { AttendancePermissions as P } from './permissions';

/**
 * T&A's contribution to the admin menu. Labels are `common:nav.att.*` (added by `./common/i18n`); paths are owned by
 * `areas/<area>/routes.tsx` — keep both in step.
 */
export const attendanceMenu: MenuEntry[] = [
  {
    key: 'att-setup',
    labelKey: 'nav.att.setupGroup',
    icon: 'setting',
    children: [
      { key: 'att-holiday-calendars', labelKey: 'nav.att.holidayCalendars', path: '/attendance/holiday-calendars', permissions: [P.HOLIDAY_CALENDAR_VIEW] },
      { key: 'att-shifts', labelKey: 'nav.att.shifts', path: '/attendance/shifts', permissions: [P.SCHEDULE_CONFIG_VIEW] },
      { key: 'att-schedule-patterns', labelKey: 'nav.att.schedulePatterns', path: '/attendance/schedule-patterns', permissions: [P.SCHEDULE_CONFIG_VIEW] },
      { key: 'att-schedule-rules', labelKey: 'nav.att.scheduleRules', path: '/attendance/schedule-rules', permissions: [P.SCHEDULE_CONFIG_VIEW] },
      { key: 'att-attendance-codes', labelKey: 'nav.att.attendanceCodes', path: '/attendance/attendance-codes', permissions: [P.ATTENDANCE_CODE_VIEW] },
      { key: 'att-leave-types', labelKey: 'nav.att.leaveTypes', path: '/attendance/leave-types', permissions: [P.LEAVE_TYPE_VIEW] },
      { key: 'att-leave-policies', labelKey: 'nav.att.leavePolicies', path: '/attendance/leave-policies', permissions: [P.LEAVE_TYPE_VIEW] },
      { key: 'att-devices', labelKey: 'nav.att.devices', path: '/attendance/devices', permissions: [P.DEVICE_VIEW] },
    ],
  },
  {
    key: 'att-time',
    labelKey: 'nav.att.timeGroup',
    icon: 'clock',
    children: [
      { key: 'att-schedule-assignments', labelKey: 'nav.att.scheduleAssignments', path: '/attendance/schedule-assignments', permissions: [P.SCHEDULE_ASSIGNMENT_VIEW] },
      { key: 'att-unscheduled', labelKey: 'nav.att.unscheduled', path: '/attendance/unscheduled-employees', permissions: [P.SCHEDULE_ASSIGNMENT_VIEW] },
      { key: 'att-enrolment-mappings', labelKey: 'nav.att.enrolmentMappings', path: '/attendance/enrolment-mappings', permissions: [P.DEVICE_VIEW] },
      { key: 'att-punch-quarantine', labelKey: 'nav.att.punchQuarantine', path: '/attendance/punches/quarantine', permissions: [P.PUNCH_VIEW] },
      { key: 'att-timesheets', labelKey: 'nav.att.timesheets', path: '/attendance/timesheets', permissions: [P.TIMESHEET_VIEW] },
      { key: 'att-employee-timesheet', labelKey: 'nav.att.employeeTimesheet', path: '/attendance/employee-timesheet', permissions: [P.TIMESHEET_VIEW] },
    ],
  },
  {
    key: 'att-leave',
    labelKey: 'nav.att.leaveGroup',
    icon: 'calendar',
    children: [
      { key: 'att-leave-requests', labelKey: 'nav.att.leaveRequests', path: '/attendance/leave-requests', permissions: [P.LEAVE_REQUEST_VIEW] },
      { key: 'att-leave-balances', labelKey: 'nav.att.leaveBalances', path: '/attendance/leave-balances', permissions: [P.LEAVE_BALANCE_VIEW] },
      { key: 'att-leave-jobs', labelKey: 'nav.att.leaveJobs', path: '/attendance/leave-jobs', permissions: [P.LEAVE_BALANCE_ADJUST] },
    ],
  },
  {
    key: 'att-overtime',
    labelKey: 'nav.att.overtimeGroup',
    icon: 'thunderbolt',
    children: [
      { key: 'att-overtime-requests', labelKey: 'nav.att.overtimeRequests', path: '/attendance/overtime-requests', permissions: [P.OVERTIME_VIEW] },
      { key: 'att-overtime-caps', labelKey: 'nav.att.overtimeCaps', path: '/attendance/overtime-caps', permissions: [P.OVERTIME_VIEW] },
    ],
  },
  {
    key: 'att-periods',
    labelKey: 'nav.att.periodsGroup',
    icon: 'lock',
    children: [
      { key: 'att-attendance-periods', labelKey: 'nav.att.periods', path: '/attendance/periods', permissions: [P.PERIOD_VIEW] },
      { key: 'att-result-imports', labelKey: 'nav.att.resultImports', path: '/attendance/result-imports', permissions: [P.RESULT_IMPORT] },
    ],
  },
  {
    key: 'att-reports',
    labelKey: 'nav.att.reportsGroup',
    icon: 'chart',
    children: [
      { key: 'att-reports-index', labelKey: 'nav.att.reports', path: '/attendance/reports', permissions: [P.TIMESHEET_VIEW, P.OVERTIME_VIEW, P.LEAVE_BALANCE_VIEW, P.PERIOD_VIEW] },
      { key: 'att-integration-inbox', labelKey: 'nav.att.integrationInbox', path: '/attendance/integration-inbox', permissions: [P.INTEGRATION_VIEW] },
      { key: 'att-jobs', labelKey: 'nav.att.jobs', path: '/attendance/jobs', permissions: [P.JOB_RUN, P.REMINDER_RUN] },
    ],
  },
  {
    key: 'att-ess',
    labelKey: 'nav.att.essGroup',
    icon: 'user',
    children: [
      { key: 'att-me-timesheet', labelKey: 'nav.att.myTimesheet', path: '/attendance/me/timesheet', permissions: [P.ESS_VIEW] },
      { key: 'att-me-schedule', labelKey: 'nav.att.mySchedule', path: '/attendance/me/schedule', permissions: [P.ESS_VIEW] },
      { key: 'att-me-leave', labelKey: 'nav.att.myLeave', path: '/attendance/me/leave', permissions: [P.ESS_VIEW] },
      { key: 'att-me-overtime', labelKey: 'nav.att.myOvertime', path: '/attendance/me/overtime', permissions: [P.ESS_VIEW] },
      { key: 'att-me-requests', labelKey: 'nav.att.myRequests', path: '/attendance/me/requests', permissions: [P.ESS_VIEW] },
    ],
  },
];
