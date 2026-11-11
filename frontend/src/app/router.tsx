import { createRouter } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { adminRoute, forbiddenRoute, loginRoute, rootRoute } from '@/shared/routing';
import { coreRoutes } from '@/modules/core';
import { peopleRoutes } from '@/modules/people';

/** Composes the route tree: every module's routes hang under the admin shell. */
export function buildRouteTree() {
  return rootRoute.addChildren([loginRoute, adminRoute.addChildren([forbiddenRoute, ...coreRoutes, ...peopleRoutes])]);
}

export function createAppRouter(queryClient: QueryClient) {
  return createRouter({
    routeTree: buildRouteTree(),
    context: { queryClient },
    defaultPreload: 'intent',
    scrollRestoration: true,
    defaultPendingMinMs: 200,
  });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
