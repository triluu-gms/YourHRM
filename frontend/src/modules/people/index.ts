import type { ShellRegistry } from '@/shared/navigation';
import { peopleMenu } from './menu';
import { employeesRoutes } from './areas/employees/routes';
import { personRoutes } from './areas/person/routes';
import { employmentRoutes } from './areas/employment/routes';
import { contractsRoutes } from './areas/contracts/routes';
import { familyRoutes } from './areas/family/routes';
import { complianceRoutes } from './areas/compliance/routes';
import { separationRoutes } from './areas/separation/routes';
import { reportsRoutes } from './areas/reports/routes';
import { essRoutes } from './areas/ess/routes';
import './common/i18n';

/** What the People module plugs into the app: menu + routes under the admin shell. */
export const peopleRegistry: Partial<ShellRegistry> = {
  menu: peopleMenu,
};

export const peopleRoutes = [
  ...employeesRoutes,
  ...personRoutes,
  ...employmentRoutes,
  ...contractsRoutes,
  ...familyRoutes,
  ...complianceRoutes,
  ...separationRoutes,
  ...reportsRoutes,
  ...essRoutes,
];
