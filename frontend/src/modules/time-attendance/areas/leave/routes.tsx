import { createRoute, type AnyRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { LeaveTypesPage } from './LeaveTypesPage';
import { LeavePoliciesPage } from './LeavePoliciesPage';
import { LeaveRequestsPage } from './LeaveRequestsPage';
import { LeaveRequestDetailPage } from './LeaveRequestDetailPage';
import { LeaveBalancesPage } from './LeaveBalancesPage';
import { LeaveJobsPage } from './LeaveJobsPage';
import './i18n';

/** Leave area (BRD 03 §4.6). Paths match `menu.ts` (`att-leave-*`). */
export const leaveTypesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/leave-types',
  beforeLoad: requirePermission(P.LEAVE_TYPE_VIEW),
  component: LeaveTypesPage,
});

export const leavePoliciesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/leave-policies',
  beforeLoad: requirePermission(P.LEAVE_TYPE_VIEW),
  component: LeavePoliciesPage,
});

export const leaveRequestsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/leave-requests',
  beforeLoad: requirePermission(P.LEAVE_REQUEST_VIEW),
  component: LeaveRequestsPage,
});

export const leaveRequestDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/leave-requests/$requestId',
  beforeLoad: requirePermission(P.LEAVE_REQUEST_VIEW),
  component: function LeaveRequestDetailRoute() {
    return <LeaveRequestDetailPage requestId={leaveRequestDetailRoute.useParams().requestId} />;
  },
});

const balancesSearch = z.object({
  employmentId: z.string().uuid().optional(),
  asOf: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  tab: z.enum(['balances', 'ledger', 'requests', 'settlement']).optional(),
});

/** `/attendance/leave-balances?employmentId=…&asOf=2026-11-01&tab=ledger` — link here from other T&A screens. */
export const leaveBalancesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/leave-balances',
  validateSearch: balancesSearch,
  beforeLoad: requirePermission(P.LEAVE_BALANCE_VIEW),
  component: function LeaveBalancesRoute() {
    const search = leaveBalancesRoute.useSearch();
    const navigate = leaveBalancesRoute.useNavigate();
    return <LeaveBalancesPage search={search} onChange={(next) => void navigate({ search: balancesSearch.parse(next), replace: true })} />;
  },
});

export const leaveJobsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/leave-jobs',
  beforeLoad: requirePermission(P.LEAVE_BALANCE_ADJUST),
  component: LeaveJobsPage,
});

export const leaveRoutes: AnyRoute[] = [
  leaveTypesRoute,
  leavePoliciesRoute,
  leaveRequestsRoute,
  leaveRequestDetailRoute,
  leaveBalancesRoute,
  leaveJobsRoute,
];
