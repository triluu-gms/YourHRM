import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { SettingsPage } from './SettingsPage';
import { SequencesPage } from './SequencesPage';

/** Settings (BR-CORE-084) and numbering (BR-CORE-085). Paths are linked from `menu.ts`; keep them. */
export const settingsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/settings',
  beforeLoad: requirePermission(P.SETTING_VIEW),
  component: SettingsPage,
});
export const sequencesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/sequences',
  beforeLoad: requirePermission(P.SEQUENCE_VIEW),
  component: SequencesPage,
});

export const configurationRoutes = [settingsRoute, sequencesRoute];
