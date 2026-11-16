import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { InboxPage } from './InboxPage';
import { DeliveriesPage } from './DeliveriesPage';
import { TemplatesPage } from './TemplatesPage';

/** Notification centre (BR-CORE-070). Paths are linked from `menu.ts` and the header bell; keep them. */
export const notificationsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/notifications',
  // every signed-in user reads their own inbox
  component: InboxPage,
});
export const notificationDeliveriesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/notification-deliveries',
  beforeLoad: requirePermission(P.NOTIFICATION_ADMIN),
  component: DeliveriesPage,
});
export const notificationTemplatesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/notification-templates',
  beforeLoad: requirePermission(P.NOTIFICATION_TEMPLATE_VIEW),
  component: TemplatesPage,
});

export const notificationsRoutes = [notificationsRoute, notificationDeliveriesRoute, notificationTemplatesRoute];
