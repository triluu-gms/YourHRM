import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { MyTimesheetPage } from './MyTimesheetPage';
import { MySchedulePage } from './MySchedulePage';
import { MyLeavePage } from './MyLeavePage';
import { MyOvertimePage } from './MyOvertimePage';
import { MyRequestsPage } from './MyRequestsPage';

/**
 * T&A self-service pages (BR-ATT-080), in the admin shell until the ESS shell exists. The employee is always the
 * caller (`att.ess.view`, SELF scope); no ids in the paths.
 */
export const myTimesheetRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/me/timesheet',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MyTimesheetPage,
});
export const myScheduleRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/me/schedule',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MySchedulePage,
});
export const myLeaveRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/me/leave',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MyLeavePage,
});
export const myOvertimeRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/me/overtime',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MyOvertimePage,
});
export const myRequestsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/me/requests',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MyRequestsPage,
});

export const essRoutes = [myTimesheetRoute, myScheduleRoute, myLeaveRoute, myOvertimeRoute, myRequestsRoute];
