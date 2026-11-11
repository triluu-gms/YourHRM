import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { Placeholder } from '../Placeholder';

/** Import engine area. Replace the placeholders with the real pages; keep the paths (menu.ts links to them). */
export const importsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/imports',
  beforeLoad: requirePermission(P.IMPORT_VIEW),
  component: () => <Placeholder area="/core/imports" />,
});

export const importsRoutes = [importsRoute ];
