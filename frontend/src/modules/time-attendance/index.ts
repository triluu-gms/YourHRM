import type { ShellRegistry } from '@/shared/navigation';
import { attendanceMenu } from './menu';
import { calendarsRoutes } from './areas/calendars/routes';
import { schedulesRoutes } from './areas/schedules/routes';
import { devicesRoutes } from './areas/devices/routes';
import { timesheetsRoutes } from './areas/timesheets/routes';
import { leaveRoutes } from './areas/leave/routes';
import { overtimeRoutes } from './areas/overtime/routes';
import { periodsRoutes } from './areas/periods/routes';
import { reportsRoutes } from './areas/reports/routes';
import { essRoutes } from './areas/ess/routes';
import './common/i18n';

/** What the Time & Attendance module plugs into the app: menu + routes under the admin shell. */
export const attendanceRegistry: Partial<ShellRegistry> = {
  menu: attendanceMenu,
};

export const attendanceRoutes = [
  ...calendarsRoutes,
  ...schedulesRoutes,
  ...devicesRoutes,
  ...timesheetsRoutes,
  ...leaveRoutes,
  ...overtimeRoutes,
  ...periodsRoutes,
  ...reportsRoutes,
  ...essRoutes,
];
