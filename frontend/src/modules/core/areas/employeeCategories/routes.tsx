import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { EmployeeCategoriesPage } from './EmployeeCategoriesPage';

/** Employee categories (BR-CORE-026). The path is linked from `menu.ts`; keep it. */
export const employeeCategoriesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/employee-categories',
  beforeLoad: requirePermission(P.EMPLOYEE_CATEGORY_VIEW),
  component: EmployeeCategoriesPage,
});

export const employeeCategoriesRoutes = [employeeCategoriesRoute];
