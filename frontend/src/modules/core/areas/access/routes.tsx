import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { UsersListPage } from './UsersListPage';
import { UserDetailPage } from './UserDetailPage';
import { RolesListPage } from './RolesListPage';
import { RoleDetailPage } from './RoleDetailPage';
import { PermissionsPage } from './PermissionsPage';
import { SupportSessionsPage } from './SupportSessionsPage';

/** Users & access area (US-CORE-030..035, 042). Paths are linked from `menu.ts`; the backend stays authoritative. */
export const usersRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/users',
  beforeLoad: requirePermission(P.USER_VIEW),
  component: UsersListPage,
});
export const userDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/users/$userId',
  beforeLoad: requirePermission(P.USER_VIEW),
  component: function UserDetailRoute() {
    return <UserDetailPage userId={userDetailRoute.useParams().userId} />;
  },
});
export const rolesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/roles',
  beforeLoad: requirePermission(P.ROLE_VIEW),
  component: RolesListPage,
});
export const roleDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/roles/$roleId',
  beforeLoad: requirePermission(P.ROLE_VIEW),
  component: function RoleDetailRoute() {
    return <RoleDetailPage roleId={roleDetailRoute.useParams().roleId} />;
  },
});
export const permissionsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/permissions',
  beforeLoad: requirePermission(P.PERMISSION_VIEW),
  component: PermissionsPage,
});
export const supportSessionsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/support-sessions',
  beforeLoad: requirePermission(P.SUPPORT_SESSION_VIEW),
  component: SupportSessionsPage,
});

export const accessRoutes = [
  usersRoute,
  userDetailRoute,
  rolesRoute,
  roleDetailRoute,
  permissionsRoute,
  supportSessionsRoute,
];
