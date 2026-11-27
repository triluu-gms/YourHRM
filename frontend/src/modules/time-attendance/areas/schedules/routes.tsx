import { createRoute, type AnyRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { ShiftsPage } from './ShiftsPage';
import { SchedulePatternsPage } from './SchedulePatternsPage';
import { ScheduleRulesPage } from './ScheduleRulesPage';
import { ScheduleAssignmentsPage } from './ScheduleAssignmentsPage';
import { UnscheduledEmployeesPage } from './UnscheduledEmployeesPage';
import './i18n';

/** Shifts, patterns, rules, assignments, unscheduled list (BRD 03 §4.2, US-ATT-008..014). Paths are linked from `menu.ts`. */
export const shiftsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/shifts',
  beforeLoad: requirePermission(P.SCHEDULE_CONFIG_VIEW),
  component: ShiftsPage,
});

export const schedulePatternsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/schedule-patterns',
  beforeLoad: requirePermission(P.SCHEDULE_CONFIG_VIEW),
  component: SchedulePatternsPage,
});

export const scheduleRulesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/schedule-rules',
  beforeLoad: requirePermission(P.SCHEDULE_CONFIG_VIEW),
  component: ScheduleRulesPage,
});

const assignmentsSearch = z.object({
  /** The employee (`employmentId`) whose schedules are shown. */
  employmentId: z.string().uuid().optional(),
  /** Assignment line to prefill a manual schedule for (from the unscheduled list). */
  assignmentKey: z.string().uuid().optional(),
});

/** `/attendance/schedule-assignments?employmentId=…[&assignmentKey=…]`. */
export const scheduleAssignmentsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/schedule-assignments',
  validateSearch: assignmentsSearch,
  beforeLoad: requirePermission(P.SCHEDULE_ASSIGNMENT_VIEW),
  component: function ScheduleAssignmentsRoute() {
    const search = scheduleAssignmentsRoute.useSearch();
    const navigate = scheduleAssignmentsRoute.useNavigate();
    return (
      <ScheduleAssignmentsPage
        employmentId={search.employmentId}
        assignmentKey={search.assignmentKey}
        onChange={(next) => void navigate({ search: next, replace: true })}
      />
    );
  },
});

export const unscheduledEmployeesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/unscheduled-employees',
  beforeLoad: requirePermission(P.SCHEDULE_ASSIGNMENT_VIEW),
  component: UnscheduledEmployeesPage,
});

export const schedulesRoutes: AnyRoute[] = [
  shiftsRoute,
  schedulePatternsRoute,
  scheduleRulesRoute,
  scheduleAssignmentsRoute,
  unscheduledEmployeesRoute,
];
