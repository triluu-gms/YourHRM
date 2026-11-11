import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { PeoplePermissions as P } from '../../permissions';
import { StatutoryReportsPage } from './StatutoryReportsPage';
import { StatutoryReportDetailPage } from './StatutoryReportDetailPage';
import { LabourBookPage } from './LabourBookPage';
import { DependantRegistrationsPage } from './DependantRegistrationsPage';
import { OpeningBalancePage } from './OpeningBalancePage';
import { IntegrationInboxPage } from './IntegrationInboxPage';

/** HR reports area (US-PPL-030/068..071/082/093). Paths are linked from `menu.ts`. */
export const statutoryReportsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/statutory-reports',
  beforeLoad: requirePermission(P.STATUTORY_REPORT_VIEW),
  component: StatutoryReportsPage,
});
export const statutoryReportDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/statutory-reports/$reportId',
  beforeLoad: requirePermission(P.STATUTORY_REPORT_VIEW),
  component: function StatutoryReportDetailRoute() {
    return <StatutoryReportDetailPage reportId={statutoryReportDetailRoute.useParams().reportId} />;
  },
});
export const labourBookRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/reports/labour-book',
  beforeLoad: requirePermission(P.STATUTORY_REPORT_VIEW),
  component: LabourBookPage,
});
export const dependantRegistrationsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/reports/dependant-registrations',
  beforeLoad: requirePermission(P.STATUTORY_REPORT_VIEW),
  component: DependantRegistrationsPage,
});
export const openingBalanceRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/opening-balance',
  beforeLoad: requirePermission(P.IMPORT_RUN),
  component: OpeningBalancePage,
});
export const integrationInboxRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/integration-inbox',
  beforeLoad: requirePermission(P.IMPORT_RUN),
  component: IntegrationInboxPage,
});

export const reportsRoutes = [
  statutoryReportsRoute,
  statutoryReportDetailRoute,
  labourBookRoute,
  dependantRegistrationsRoute,
  openingBalanceRoute,
  integrationInboxRoute,
];
