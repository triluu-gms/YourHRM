import { createRoute, type AnyRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { PeriodsListPage } from './PeriodsListPage';
import { PeriodDetailPage } from './PeriodDetailPage';
import { AttendanceResultPage } from './AttendanceResultPage';
import { ResultImportsPage } from './ResultImportsPage';
import './i18n';

export const periodsListRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/periods',
  beforeLoad: requirePermission(P.PERIOD_VIEW),
  component: PeriodsListPage,
});

export const periodDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/periods/$periodId',
  beforeLoad: requirePermission(P.PERIOD_VIEW),
  component: function PeriodDetailRoute() {
    return <PeriodDetailPage periodId={periodDetailRoute.useParams().periodId} />;
  },
});

export const attendanceResultRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/attendance-results/$resultId',
  beforeLoad: requirePermission(P.PERIOD_VIEW),
  component: function AttendanceResultRoute() {
    return <AttendanceResultPage resultId={attendanceResultRoute.useParams().resultId} />;
  },
});

const importSearch = z.object({ jobId: z.string().uuid().optional() });

/** `/attendance/result-imports?jobId=…` — link here from Core's import job of type `ATT_RESULT_IMPORT`. */
export const resultImportsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/result-imports',
  validateSearch: importSearch,
  beforeLoad: requirePermission(P.RESULT_IMPORT),
  component: function ResultImportsRoute() {
    const search = resultImportsRoute.useSearch();
    const navigate = resultImportsRoute.useNavigate();
    return <ResultImportsPage jobId={search.jobId} onJobChange={(jobId) => void navigate({ search: { jobId }, replace: true })} />;
  },
});

/** Routes of the T&A `periods` area (BRD §4.8, close-hold protocol ADR-0019). */
export const periodsRoutes: AnyRoute[] = [periodsListRoute, periodDetailRoute, attendanceResultRoute, resultImportsRoute];
