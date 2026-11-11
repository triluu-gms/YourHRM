import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { Placeholder } from '../Placeholder';

/** Settings and numbering area. Replace the placeholders with the real pages; keep the paths (menu.ts links to them). */
export const settingsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/settings',
  beforeLoad: requirePermission(P.SETTING_VIEW, P.SEQUENCE_VIEW),
  component: () => <Placeholder area="/core/settings" />,
});
export const sequencesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/sequences',
  beforeLoad: requirePermission(P.SETTING_VIEW, P.SEQUENCE_VIEW),
  component: () => <Placeholder area="/core/sequences" />,
});

export const configurationRoutes = [settingsRoute, sequencesRoute ];
