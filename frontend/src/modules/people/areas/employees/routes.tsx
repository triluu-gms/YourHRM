import { createRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { PeoplePermissions as P } from '../../permissions';
import { EmployeesListPage } from './EmployeesListPage';
import { EmployeeProfilePage } from './EmployeeProfilePage';

export const employeesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/employees',
  beforeLoad: requirePermission(P.EMPLOYEE_VIEW),
  component: EmployeesListPage,
});

const profileSearch = z.object({
  /** Profile tab key (`ShellTabKey`). */
  tab: z.string().optional(),
  /** As-of date `YYYY-MM-DD`; default today. */
  asOf: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
});

/** `/people/employees/$employmentId?tab=contracts&asOf=2026-01-01` — link here from anywhere in People. */
export const employeeProfileRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/employees/$employmentId',
  validateSearch: profileSearch,
  beforeLoad: requirePermission(P.EMPLOYEE_VIEW),
  component: function EmployeeProfileRoute() {
    const { employmentId } = employeeProfileRoute.useParams();
    const search = employeeProfileRoute.useSearch();
    const navigate = employeeProfileRoute.useNavigate();
    return (
      <EmployeeProfilePage
        employmentId={employmentId}
        tab={search.tab}
        asOf={search.asOf}
        onChange={(next) => void navigate({ search: next, replace: true })}
      />
    );
  },
});

export const employeesRoutes = [employeesRoute, employeeProfileRoute];
