import { createRoute, type AnyRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { OvertimeRequestsPage } from './OvertimeRequestsPage';
import { OvertimeRequestDetailPage } from './OvertimeRequestDetailPage';
import { OvertimeCapsPage } from './OvertimeCapsPage';
import './i18n';

const employmentSearch = z.object({ employmentId: z.string().uuid().optional() });
const capsSearch = employmentSearch.extend({ legalEntityId: z.string().uuid().optional() });

/** `/attendance/overtime-requests?employmentId=…` — requests and paid results of one employee. */
export const overtimeRequestsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/overtime-requests',
  validateSearch: employmentSearch,
  beforeLoad: requirePermission(P.OVERTIME_VIEW),
  component: function OvertimeRequestsRoute() {
    const search = overtimeRequestsRoute.useSearch();
    const navigate = overtimeRequestsRoute.useNavigate();
    return (
      <OvertimeRequestsPage
        employmentId={search.employmentId}
        onEmploymentChange={(employmentId) => void navigate({ search: { employmentId }, replace: true })}
      />
    );
  },
});

export const overtimeRequestDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/overtime-requests/$requestId',
  beforeLoad: requirePermission(P.OVERTIME_VIEW),
  component: function OvertimeRequestDetailRoute() {
    return <OvertimeRequestDetailPage requestId={overtimeRequestDetailRoute.useParams().requestId} />;
  },
});

export const overtimeCapsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/overtime-caps',
  validateSearch: capsSearch,
  beforeLoad: requirePermission(P.OVERTIME_VIEW),
  component: function OvertimeCapsRoute() {
    const search = overtimeCapsRoute.useSearch();
    const navigate = overtimeCapsRoute.useNavigate();
    return (
      <OvertimeCapsPage
        employmentId={search.employmentId}
        legalEntityId={search.legalEntityId}
        onChange={(next) => void navigate({ search: next, replace: true })}
      />
    );
  },
});

/** Routes of the T&A `overtime` area (BR-ATT-040..049). */
export const overtimeRoutes: AnyRoute[] = [overtimeRequestsRoute, overtimeRequestDetailRoute, overtimeCapsRoute];
