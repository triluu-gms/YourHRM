import { redirect } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { ApiProblem } from '@/shared/api';
import { hasPermission, meQueryOptions, type Me } from './me';

export interface RouterContext {
  queryClient: QueryClient;
}

/** Loads `/me` once per session (cached); 401/403 send the user to the login page with the return URL. */
export async function requireSession(context: RouterContext, href: string): Promise<{ me: Me }> {
  try {
    const me = await context.queryClient.ensureQueryData(meQueryOptions());
    return { me };
  } catch (error) {
    if (error instanceof ApiProblem && (error.status === 401 || error.status === 403)) {
      throw redirect({ to: '/login', search: { redirect: href, reason: error.code } });
    }
    throw error;
  }
}

/** `beforeLoad` helper for a route that needs one of the given permission codes. */
export function requirePermission(...codes: string[]) {
  return ({ context }: { context: { me: Me } }) => {
    if (!hasPermission(context.me, ...codes)) {
      throw redirect({ to: '/forbidden', search: { permission: codes.join(',') } });
    }
  };
}
