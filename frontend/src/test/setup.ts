import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { cleanup, configure } from '@testing-library/react';
import { server } from './server';
import '@/shared/i18n';

// Ant Design pages are heavy in jsdom; the default 1 s for findBy*/waitFor flakes on a cold run, and 3 s still
// flakes when many module test files run in parallel workers. Keep well under `testTimeout` (15 s).
configure({ asyncUtilTimeout: 8000 });

// Ant Design needs matchMedia and ResizeObserver in jsdom.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver = ResizeObserverStub;
// jsdom resolves the SPA's relative URLs against http://localhost:3000 — the origin of the MSW handlers.
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());
