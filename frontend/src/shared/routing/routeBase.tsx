import { Outlet, createRootRouteWithContext, createRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { requireSession, type RouterContext } from '@/shared/auth';
import { AdminLayout } from '@/shared/layouts/AdminLayout';
import { ForbiddenPage, NotFoundPage, RouteErrorPage } from '@/shared/layouts/StatusPages';
import { LoginPage } from '@/shared/layouts/LoginPage';

/**
 * Route skeleton shared by every module. Modules hang their routes under {@link adminRoute}
 * (`getParentRoute: () => adminRoute`) and never import `router.tsx` (circular).
 */
export const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: () => <Outlet />,
  notFoundComponent: NotFoundPage,
  errorComponent: RouteErrorPage,
});

export const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  validateSearch: z.object({ redirect: z.string().optional(), reason: z.string().optional() }),
  component: LoginPage,
});

/** Authenticated shell: `/me` is loaded here once and exposed to children as `context.me`. */
export const adminRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'admin',
  beforeLoad: ({ context, location }) => requireSession(context, location.href),
  component: AdminLayout,
});

export const forbiddenRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/forbidden',
  validateSearch: z.object({ permission: z.string().optional() }),
  component: ForbiddenPage,
});
