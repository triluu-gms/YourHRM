/** Browser session with the BFF (ADR-0014). Same contract for hrm-bff and the dev stand-in. */
export interface BffSession {
  authenticated: boolean;
  username?: string | null;
}

export async function bffSession(): Promise<BffSession> {
  const res = await fetch('/bff/session', { credentials: 'same-origin' });
  if (!res.ok) return { authenticated: false };
  return (await res.json()) as BffSession;
}

export async function bffLogin(username: string, password: string): Promise<BffSession> {
  const res = await fetch('/bff/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    const problem = (await res.json().catch(() => ({}))) as { detail?: string; code?: string };
    throw Object.assign(new Error(problem.detail ?? `HTTP ${res.status}`), { code: problem.code ?? 'login-failed' });
  }
  return (await res.json()) as BffSession;
}

export async function bffLogout(): Promise<void> {
  await fetch('/bff/logout', { method: 'POST', credentials: 'same-origin' });
}

/** Real BFF: redirect-based Authorization Code + PKCE; dev: the SPA login form. */
export const loginMode: 'form' | 'redirect' = import.meta.env.VITE_BFF_LOGIN_MODE === 'redirect' ? 'redirect' : 'form';

export function redirectToIdpLogin(returnTo: string) {
  window.location.assign(`/bff/login?redirect=${encodeURIComponent(returnTo)}`);
}
