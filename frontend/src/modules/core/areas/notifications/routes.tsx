import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { Placeholder } from '../Placeholder';

/** Notification inbox, deliveries, templates area. Replace the placeholders with the real pages; keep the paths (menu.ts links to them). */
export const notificationsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/notifications',
  component: () => <Placeholder area="/core/notifications" />,
});
export const notificationDeliveriesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/notification-deliveries',
  component: () => <Placeholder area="/core/notification-deliveries" />,
});
export const notificationTemplatesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/notification-templates',
  component: () => <Placeholder area="/core/notification-templates" />,
});

export const notificationsRoutes = [notificationsRoute, notificationDeliveriesRoute, notificationTemplatesRoute ];
