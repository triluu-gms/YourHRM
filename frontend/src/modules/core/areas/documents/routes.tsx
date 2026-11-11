import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { Placeholder } from '../Placeholder';

/** Document library and templates area. Replace the placeholders with the real pages; keep the paths (menu.ts links to them). */
export const documentsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/documents',
  component: () => <Placeholder area="/core/documents" />,
});
export const documentTemplatesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/document-templates',
  component: () => <Placeholder area="/core/document-templates" />,
});

export const documentsRoutes = [documentsRoute, documentTemplatesRoute ];
