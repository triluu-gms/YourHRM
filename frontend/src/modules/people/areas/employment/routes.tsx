import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { PeoplePermissions as P } from '../../permissions';
import { SalaryRecordsPage } from './SalaryRecordsPage';
import { ConcurrentEmploymentPage } from './ConcurrentEmploymentPage';

/** Employment-area pages; the employment / assignments / compensation tabs live in the employee profile. */
export const salaryRecordsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/salary-records',
  beforeLoad: requirePermission(P.COMPENSATION_VIEW),
  component: SalaryRecordsPage,
});
export const concurrentEmploymentRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/reports/concurrent-employment',
  beforeLoad: requirePermission(P.EMPLOYEE_VIEW),
  component: ConcurrentEmploymentPage,
});

export const employmentRoutes = [salaryRecordsRoute, concurrentEmploymentRoute];
