import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { PeoplePermissions as P } from '../../permissions';
import { ExpiringDocumentsPage } from './ExpiringDocumentsPage';
import { ResidencyProposalsPage } from './ResidencyProposalsPage';

export const expiringDocumentsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/expiring-documents',
  beforeLoad: requirePermission(P.EMPLOYEE_VIEW),
  component: ExpiringDocumentsPage,
});
export const residencyProposalsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/residency-proposals',
  beforeLoad: requirePermission(P.TAX_RESIDENCY_UPDATE),
  component: ResidencyProposalsPage,
});

export const complianceRoutes = [expiringDocumentsRoute, residencyProposalsRoute];
