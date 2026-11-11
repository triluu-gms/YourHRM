import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { PeoplePermissions as P } from '../../permissions';
import { HirePage } from './HirePage';

/** Direct-hire wizard (US-PPL-018/020/021); the person tabs live in the employee profile. */
export const hireRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/hires/new',
  beforeLoad: requirePermission(P.EMPLOYMENT_CREATE),
  component: HirePage,
});

export const personRoutes = [hireRoute];
