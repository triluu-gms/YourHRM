import { QueryClient } from '@tanstack/react-query';
import { ApiProblem } from './http';

/** One client per app; 401/403/404/409/422 are business answers, never retried. */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          if (error instanceof ApiProblem && error.status < 500) return false;
          return failureCount < 2;
        },
      },
      mutations: { retry: false },
    },
  });
}
