import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { Placeholder } from '../Placeholder';

/** Audit log area. Replace the placeholders with the real pages; keep the paths (menu.ts links to them). */
export const auditRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/audit',
  beforeLoad: requirePermission(P.AUDIT_VIEW),
  component: () => <Placeholder area="/core/audit" />,
});

export const auditRoutes = [auditRoute ];
