import { describe, expect, it } from 'vitest';
import { API, mswHttp, problem, server } from '@/test/server';
import { ApiProblem, buildQuery, http } from './http';

describe('http', () => {
  it('serialises params and parses JSON', async () => {
    server.use(mswHttp.get(`${API}/core/users`, ({ request }) => {
      const url = new URL(request.url);
      return Response.json({ items: [], page: Number(url.searchParams.get('page')), size: 20, total: 0 });
    }));
    const page = await http.get<{ page: number }>('/core/users', { params: { page: 2, size: 20, q: '' } });
    expect(page.page).toBe(2);
  });

  it('throws ApiProblem with the code of a problem+json body', async () => {
    server.use(mswHttp.put(`${API}/core/users/1`, () => problem(409, 'stale-version', 'changed')));
    await expect(http.put('/core/users/1', { version: 1 })).rejects.toSatisfy(
      (e: unknown) => e instanceof ApiProblem && e.code === 'stale-version' && e.status === 409,
    );
  });

  it('builds query strings skipping empty values and repeating arrays', () => {
    expect(buildQuery({ a: 1, b: undefined, c: '', d: ['x', 'y'], e: false })).toBe('?a=1&d=x&d=y&e=false');
  });
});
