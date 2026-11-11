import { useEffect, useMemo } from 'react';
import { RouterProvider } from '@tanstack/react-router';
import { createQueryClient, onUnauthenticated } from '@/shared/api';
import { meQueryOptions } from '@/shared/auth';
import { changeLanguage, toUiLanguage } from '@/shared/i18n';
import { mergeRegistries, ShellRegistryProvider } from '@/shared/navigation';
import { coreRegistry } from '@/modules/core';
import { peopleRegistry } from '@/modules/people';
import { AppProviders } from './providers';
import { createAppRouter } from './router';

export function App() {
  const queryClient = useMemo(() => createQueryClient(), []);
  const router = useMemo(() => createAppRouter(queryClient), [queryClient]);
  const registry = useMemo(() => mergeRegistries([coreRegistry, peopleRegistry]), []);

  useEffect(() => {
    // Session expired mid-work: drop the cached /me and send the user to the login page.
    return onUnauthenticated(() => {
      queryClient.removeQueries({ queryKey: meQueryOptions().queryKey });
      void router.navigate({ to: '/login', search: { redirect: router.state.location.href, reason: 'unauthenticated' } });
    });
  }, [queryClient, router]);

  useEffect(() => {
    // UI language follows the user's locale from /me (NFR-SAAS-013).
    const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
      if (event.type === 'updated' && event.query.queryKey[1] === 'me' && event.query.state.data) {
        const locale = (event.query.state.data as { locale?: string }).locale;
        void changeLanguage(toUiLanguage(locale));
      }
    });
    return unsubscribe;
  }, [queryClient]);

  return (
    <AppProviders queryClient={queryClient}>
      <ShellRegistryProvider registry={registry}>
        <RouterProvider router={router} />
      </ShellRegistryProvider>
    </AppProviders>
  );
}
