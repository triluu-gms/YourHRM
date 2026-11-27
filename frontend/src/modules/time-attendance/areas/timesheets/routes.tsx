import { createRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { AttendanceCodesPage } from './AttendanceCodesPage';
import { TimesheetsPage } from './TimesheetsPage';
import { EmployeeTimesheetPage } from './EmployeeTimesheetPage';
import './i18n';

/** Daily timesheets (BRD 03 §4.4). Paths are linked from `menu.ts`. */
export const attendanceCodesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/attendance-codes',
  beforeLoad: requirePermission(P.ATTENDANCE_CODE_VIEW),
  component: AttendanceCodesPage,
});

export const timesheetsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/timesheets',
  beforeLoad: requirePermission(P.TIMESHEET_VIEW),
  component: TimesheetsPage,
});

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

/** `/attendance/employee-timesheet?employmentId=…&from=2026-11-01&to=2026-11-30`. */
export const employeeTimesheetSearch = z.object({
  employmentId: z.string().uuid().optional(),
  from: isoDate.optional(),
  to: isoDate.optional(),
});

export const employeeTimesheetRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/employee-timesheet',
  validateSearch: employeeTimesheetSearch,
  beforeLoad: requirePermission(P.TIMESHEET_VIEW),
  component: function EmployeeTimesheetRoute() {
    const search = employeeTimesheetRoute.useSearch();
    const navigate = employeeTimesheetRoute.useNavigate();
    return <EmployeeTimesheetPage search={search} onChange={(next) => void navigate({ search: next, replace: true })} />;
  },
});

export const timesheetsRoutes = [attendanceCodesRoute, timesheetsRoute, employeeTimesheetRoute];
