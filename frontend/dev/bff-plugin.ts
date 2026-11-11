import type { IncomingMessage, ServerResponse } from 'node:http';
import { randomBytes } from 'node:crypto';
import type { Plugin } from 'vite';

/**
 * Development stand-in for hrm-bff (ADR-0014): the browser never sees a token.
 *
 * - `GET  /bff/session`  → `{ authenticated, username }`
 * - `POST /bff/login`    → `{ username, password }`; Resource Owner Password grant against Keycloak
 *                          (or any credentials when `staticToken` is configured); sets the HttpOnly cookie
 * - `POST /bff/logout`   → drops the server-side session
 * - `/api/**`            → proxied to the backend with `Authorization: Bearer <token of the session>`
 *
 * Sessions live in memory of the dev server only. Not for production.
 */
export interface DevBffOptions {
  apiTarget: string;
  keycloak: { issuer: string; clientId: string; clientSecret?: string };
  staticToken?: string;
}

interface Session {
  username: string;
  accessToken: string;
  refreshToken?: string;
  expiresAt: number;
}

const COOKIE = 'yhrm_sid';

export function devBff(options: DevBffOptions): Plugin {
  const sessions = new Map<string, Session>();

  const sessionOf = (req: IncomingMessage): { sid: string; session: Session } | undefined => {
    const sid = parseCookies(req.headers.cookie)[COOKIE];
    if (!sid) return undefined;
    const session = sessions.get(sid);
    return session ? { sid, session } : undefined;
  };

  async function refreshIfNeeded(sid: string, session: Session): Promise<Session> {
    if (options.staticToken || Date.now() < session.expiresAt - 30_000 || !session.refreshToken) return session;
    const tokens = await tokenRequest(options.keycloak, {
      grant_type: 'refresh_token',
      refresh_token: session.refreshToken,
    });
    const next: Session = {
      username: session.username,
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token ?? session.refreshToken,
      expiresAt: Date.now() + tokens.expires_in * 1000,
    };
    sessions.set(sid, next);
    return next;
  }

  return {
    name: 'yhrm-dev-bff',
    config: () => ({
      server: {
        proxy: {
          '/api': {
            target: options.apiTarget,
            changeOrigin: true,
            // SSE (/notifications/stream) must not be buffered
            configure(proxy) {
              proxy.on('proxyReq', (proxyReq, req) => {
                const token = (req as IncomingMessage & { yhrmToken?: string }).yhrmToken;
                if (token) proxyReq.setHeader('Authorization', `Bearer ${token}`);
              });
            },
          },
        },
      },
    }),
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = req.url ?? '';
        try {
          if (url.startsWith('/api/')) {
            const found = sessionOf(req);
            if (!found) {
              problem(res, 401, 'unauthenticated', 'No browser session; sign in first');
              return;
            }
            let session: Session;
            try {
              session = await refreshIfNeeded(found.sid, found.session);
            } catch (err) {
              const e = err as Error & { code?: string };
              if (e.code === 'idp-unreachable') throw err;
              // Refresh token rejected (expired, revoked, or Keycloak was reset): drop the session so the SPA signs in again.
              sessions.delete(found.sid);
              res.setHeader('Set-Cookie', `${COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0`);
              problem(res, 401, 'unauthenticated', `Session expired (${e.message}); sign in again`);
              return;
            }
            (req as IncomingMessage & { yhrmToken?: string }).yhrmToken = session.accessToken;
            next();
            return;
          }
          if (url === '/bff/session' && req.method === 'GET') {
            const found = sessionOf(req);
            json(res, 200, { authenticated: !!found, username: found?.session.username ?? null });
            return;
          }
          if (url === '/bff/login' && req.method === 'POST') {
            const body = (await readJson(req)) as { username?: string; password?: string };
            if (!body.username || !body.password) {
              problem(res, 400, 'validation-failed', 'username and password are required');
              return;
            }
            let session: Session;
            if (options.staticToken) {
              session = { username: body.username, accessToken: options.staticToken, expiresAt: Number.MAX_SAFE_INTEGER };
            } else {
              const tokens = await tokenRequest(options.keycloak, {
                grant_type: 'password',
                username: body.username,
                password: body.password,
                scope: 'openid',
              });
              session = {
                username: body.username,
                accessToken: tokens.access_token,
                refreshToken: tokens.refresh_token,
                expiresAt: Date.now() + tokens.expires_in * 1000,
              };
            }
            const sid = randomBytes(24).toString('base64url');
            sessions.set(sid, session);
            res.setHeader('Set-Cookie', `${COOKIE}=${sid}; Path=/; HttpOnly; SameSite=Strict`);
            json(res, 200, { authenticated: true, username: session.username });
            return;
          }
          if (url === '/bff/login' && req.method === 'GET') {
            // Redirect-mode login of the real BFF: in dev, send the user to the SPA login form.
            res.statusCode = 302;
            res.setHeader('Location', '/login');
            res.end();
            return;
          }
          if (url === '/bff/logout' && req.method === 'POST') {
            const found = sessionOf(req);
            if (found) sessions.delete(found.sid);
            res.setHeader('Set-Cookie', `${COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0`);
            res.statusCode = 204;
            res.end();
            return;
          }
          next();
        } catch (err) {
          const e = err as Error & { status?: number; code?: string };
          problem(res, e.status ?? 502, e.code ?? 'bff-error', e.message);
        }
      });
    },
  };
}

interface TokenResponse {
  access_token: string;
  refresh_token?: string;
  expires_in: number;
}

async function tokenRequest(
  kc: DevBffOptions['keycloak'],
  params: Record<string, string>,
): Promise<TokenResponse> {
  const form = new URLSearchParams({ client_id: kc.clientId, ...params });
  if (kc.clientSecret) form.set('client_secret', kc.clientSecret);
  const response = await fetch(`${kc.issuer}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form,
  }).catch((e: Error) => {
    throw Object.assign(new Error(`Keycloak unreachable at ${kc.issuer}: ${e.message}`), {
      status: 502,
      code: 'idp-unreachable',
    });
  });
  if (!response.ok) {
    const text = await response.text();
    let detail = text;
    try {
      const parsed = JSON.parse(text) as { error_description?: string; error?: string };
      detail = parsed.error_description ?? parsed.error ?? text;
    } catch {
      /* plain text */
    }
    throw Object.assign(new Error(detail), {
      status: response.status === 401 ? 401 : 502,
      code: response.status === 401 ? 'invalid-credentials' : 'idp-error',
    });
  }
  return (await response.json()) as TokenResponse;
}

function parseCookies(header: string | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of (header ?? '').split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k) out[k] = decodeURIComponent(v.join('='));
  }
  return out;
}

function readJson(req: IncomingMessage): Promise<unknown> {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk: Buffer) => {
      data += chunk.toString('utf8');
    });
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function json(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify(body));
}

function problem(res: ServerResponse, status: number, code: string, detail: string) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/problem+json; charset=utf-8');
  res.end(JSON.stringify({ type: `urn:yourhrm:problem:${code}`, title: code, status, detail, code }));
}
