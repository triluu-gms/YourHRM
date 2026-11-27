import { createRoute, type RouteComponent } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { ReportsIndexPage } from './ReportsIndexPage';
import { DailyExceptionsReportPage, PunchLogReportPage, TimesheetGridReportPage } from './TimesheetReports';
import { OvertimeCapOverridesReportPage, OvertimeSummaryReportPage } from './OvertimeReports';
import { LeaveBalancesReportPage, LeaveTakenReportPage, SiLeaveRegisterReportPage } from './LeaveReports';
import { PeriodCloseStatusReportPage, RetroAdjustmentsReportPage } from './PeriodReports';
import { IntegrationInboxPage } from './IntegrationInboxPage';
import { JobsPage } from './JobsPage';
import { REPORT_PERMISSIONS, reportDef, type ReportKey } from './shared';

/**
 * T&A reports (BRD 03 §8), integration inbox and manual jobs. Paths are linked from `menu.ts`; each report route is
 * guarded by the view permission its endpoint checks (Excel additionally needs `att.report.export`).
 */
export const reportsIndexRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/reports',
  beforeLoad: requirePermission(...REPORT_PERMISSIONS),
  component: ReportsIndexPage,
});

const reportRoute = (key: ReportKey, component: RouteComponent) =>
  createRoute({
    getParentRoute: () => adminRoute,
    path: `/attendance/reports/${key}`,
    beforeLoad: requirePermission(reportDef(key).permission),
    component,
  });

export const timesheetGridRoute = reportRoute('timesheet-grid', TimesheetGridReportPage);
export const dailyExceptionsRoute = reportRoute('daily-exceptions', DailyExceptionsReportPage);
export const punchLogRoute = reportRoute('punch-log', PunchLogReportPage);
export const overtimeSummaryRoute = reportRoute('overtime-summary', OvertimeSummaryReportPage);
export const overtimeCapOverridesRoute = reportRoute('overtime-cap-overrides', OvertimeCapOverridesReportPage);
export const leaveBalancesReportRoute = reportRoute('leave-balances', LeaveBalancesReportPage);
export const leaveTakenRoute = reportRoute('leave-taken', LeaveTakenReportPage);
export const siLeaveRegisterRoute = reportRoute('si-leave-register', SiLeaveRegisterReportPage);
export const periodCloseStatusRoute = reportRoute('period-close-status', PeriodCloseStatusReportPage);
export const retroAdjustmentsRoute = reportRoute('retro-adjustments', RetroAdjustmentsReportPage);

export const integrationInboxRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/integration-inbox',
  beforeLoad: requirePermission(P.INTEGRATION_VIEW),
  component: IntegrationInboxPage,
});

export const jobsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/jobs',
  beforeLoad: requirePermission(P.JOB_RUN, P.REMINDER_RUN),
  component: JobsPage,
});

export const reportsRoutes = [
  reportsIndexRoute,
  timesheetGridRoute,
  dailyExceptionsRoute,
  punchLogRoute,
  overtimeSummaryRoute,
  overtimeCapOverridesRoute,
  leaveBalancesReportRoute,
  leaveTakenRoute,
  siLeaveRegisterRoute,
  periodCloseStatusRoute,
  retroAdjustmentsRoute,
  integrationInboxRoute,
  jobsRoute,
];
