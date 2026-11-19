import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { MasterDataPage } from './MasterDataPage';

/**
 * Global master data browser (BR-CORE-080..082). The endpoints are read-only reference data every signed-in user
 * may read (`@PublicEndpoint`), so the route needs no permission. The path is linked from `menu.ts`; keep it.
 */
export const masterDataRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/master-data',
  component: MasterDataPage,
});

export const masterDataRoutes = [masterDataRoute];
