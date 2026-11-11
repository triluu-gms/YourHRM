import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { Placeholder } from '../Placeholder';

/** Global master data browser area. Replace the placeholders with the real pages; keep the paths (menu.ts links to them). */
export const masterDataRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/master-data',
  component: () => <Placeholder area="/core/master-data" />,
});

export const masterDataRoutes = [masterDataRoute ];
