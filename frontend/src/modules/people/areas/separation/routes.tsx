import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { PeoplePermissions as P } from '../../permissions';
import { SeparationsListPage } from './SeparationsListPage';
import { SeparationDetailPage } from './SeparationDetailPage';

/** Separations (US-PPL-060..067). The profile tab and header actions live in `tabs.tsx` / `ProfileActions.tsx`. */
export const separationsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/separations',
  beforeLoad: requirePermission(P.SEPARATION_VIEW),
  component: SeparationsListPage,
});

export const separationDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/separations/$separationId',
  beforeLoad: requirePermission(P.SEPARATION_VIEW),
  component: function SeparationDetailRoute() {
    return <SeparationDetailPage separationId={separationDetailRoute.useParams().separationId} />;
  },
});

export const separationRoutes = [separationsRoute, separationDetailRoute];
