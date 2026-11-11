import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { meFixture } from './fixtures';

export const API = 'http://localhost:3000/api/v1';

/** Default handlers every test gets; add area handlers with `server.use(...)`. */
export const defaultHandlers = [
  http.get(`${API}/core/me`, () => HttpResponse.json(meFixture)),
  http.get(`${API}/core/notifications/unread-count`, () => HttpResponse.json({ unread: 0 })),
  http.get(`${API}/core/notifications`, () => HttpResponse.json({ items: [], unread: 0, page: 0, size: 5 })),
  http.get(`${API}/core/workflow/tasks/counts`, () => HttpResponse.json({})),
];

export const server = setupServer(...defaultHandlers);

/** RFC 9457 problem for a handler. */
export function problem(status: number, code: string, detail?: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `urn:yourhrm:problem:${code}`, title: code, status, detail, code, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

export { http as mswHttp, HttpResponse };
