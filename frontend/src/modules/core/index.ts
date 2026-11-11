import type { ShellRegistry } from '@/shared/navigation';
import { coreMenu } from './menu';
import { NotificationBell } from './areas/notifications/NotificationBell';
import { TaskBadge } from './areas/workflow/TaskBadge';
import { homeRoutes } from './areas/home/routes';
import { meRoutes } from './areas/me/routes';
import { employerRoutes } from './areas/employer/routes';
import { jobCatalogueRoutes } from './areas/jobCatalogue/routes';
import { accessRoutes } from './areas/access/routes';
import { workflowRoutes } from './areas/workflow/routes';
import { notificationsRoutes } from './areas/notifications/routes';
import { documentsRoutes } from './areas/documents/routes';
import { importsRoutes } from './areas/imports/routes';
import { configurationRoutes } from './areas/configuration/routes';
import { masterDataRoutes } from './areas/masterData/routes';
import { auditRoutes } from './areas/audit/routes';
import './i18n';

/** What the Core module plugs into the app: shell registry + routes under the admin shell. */
export const coreRegistry: Partial<ShellRegistry> = {
  menu: coreMenu,
  headerWidgets: [TaskBadge, NotificationBell],
  homePath: '/',
};

export const coreRoutes = [
  ...homeRoutes,
  ...meRoutes,
  ...employerRoutes,
  ...jobCatalogueRoutes,
  ...accessRoutes,
  ...workflowRoutes,
  ...notificationsRoutes,
  ...documentsRoutes,
  ...importsRoutes,
  ...configurationRoutes,
  ...masterDataRoutes,
  ...auditRoutes,
];
