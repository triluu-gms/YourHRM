import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { CostCentresPage } from './CostCentresPage';

/** Cost centres (BR-CORE-025). The path is linked from `menu.ts`; keep it. */
export const costCentresRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/cost-centres',
  beforeLoad: requirePermission(P.COST_CENTRE_VIEW),
  component: CostCentresPage,
});

export const costCentresRoutes = [costCentresRoute];
