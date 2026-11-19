import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { DocumentsPage } from './DocumentsPage';
import { DocumentTemplatesPage } from './DocumentTemplatesPage';

/** Documents and templates (BR-CORE-100..102). The paths are linked from `menu.ts`; keep them. */
export const documentsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/documents',
  // no permission of its own: the document types are reference data, and every file is checked on its owner record
  component: DocumentsPage,
});
export const documentTemplatesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/document-templates',
  beforeLoad: requirePermission(P.DOCUMENT_TEMPLATE_VIEW),
  component: DocumentTemplatesPage,
});

export const documentsRoutes = [documentsRoute, documentTemplatesRoute];
