import { Suspense, type ReactElement, type ReactNode } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  createMemoryHistory,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router';
import { App as AntApp, ConfigProvider } from 'antd';
import type { RouterContext } from '@/shared/auth';
import { mergeRegistries, ShellRegistryProvider, type ShellRegistry } from '@/shared/navigation';

export function testQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
}

interface Options extends Omit<RenderOptions, 'wrapper'> {
  /** Initial URL; the component is mounted at a catch-all route so links resolve. */
  path?: string;
  queryClient?: QueryClient;
  registry?: Partial<ShellRegistry>;
}

/**
 * Renders a page inside providers + a memory router. The element is the component of a `$` route so that
 * `useParams`/`useSearch` with `strict: false` and `<Link>` work. Data comes from MSW (`src/test/server.ts`).
 */
export function renderPage(ui: ReactElement, { path = '/', queryClient = testQueryClient(), registry, ...options }: Options = {}) {
  const rootRoute = createRootRouteWithContext<RouterContext>()({ component: () => <Outlet /> });
  const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: () => ui });
  const catchAll = createRoute({ getParentRoute: () => rootRoute, path: '$', component: () => ui });
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, catchAll]),
    history: createMemoryHistory({ initialEntries: [path] }),
    context: { queryClient },
  });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <ShellRegistryProvider registry={mergeRegistries([registry ?? {}])}>
            <Suspense fallback={<div>loading</div>}>{children}</Suspense>
          </ShellRegistryProvider>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  );
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const result = render(<RouterProvider router={router as any} />, { wrapper: Wrapper, ...options });
  return { ...result, router, queryClient };
}

export * from '@testing-library/react';
export { default as userEvent } from '@testing-library/user-event';
