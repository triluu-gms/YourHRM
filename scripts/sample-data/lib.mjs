// Shared helpers of the sample-data script (LOCAL DEVELOPMENT ONLY). Node 22+: global fetch / FormData / Blob.

export const CFG = {
  api: process.env.YHRM_API_URL ?? 'http://localhost:8080/api/v1',
  keycloak: process.env.YHRM_KEYCLOAK_URL ?? 'http://localhost:8180',
  realm: 'yourhrm',
  clientId: 'yourhrm-web',
  kcAdmin: process.env.KEYCLOAK_ADMIN ?? 'admin',
  kcAdminPassword: process.env.KEYCLOAK_ADMIN_PASSWORD ?? 'admin_dev',
  owner: { username: 'admin', password: 'admin_dev' },
};

export class ApiError extends Error {
  constructor(method, path, status, body) {
    super(`${method} ${path} -> ${status}: ${typeof body === 'string' ? body : JSON.stringify(body)}`);
    this.status = status;
    this.body = body;
  }
}

const tokens = new Map();

/** Access token of a realm user (Resource Owner Password grant, as the dev BFF does). */
export async function login(username, password) {
  const cached = tokens.get(username);
  if (cached && cached.expiresAt > Date.now() + 30_000) return cached.token;
  const res = await fetch(`${CFG.keycloak}/realms/${CFG.realm}/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({ grant_type: 'password', client_id: CFG.clientId, username, password }),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`login ${username}: ${JSON.stringify(json)}`);
  tokens.set(username, { token: json.access_token, expiresAt: Date.now() + json.expires_in * 1000 });
  return json.access_token;
}

/** A client bound to one user: api.get/post/put/del/upload. */
export function client(user) {
  async function call(method, path, body, { query, raw } = {}) {
    const token = await login(user.username, user.password);
    const qs = query ? '?' + new URLSearchParams(Object.entries(query).filter(([, v]) => v != null)) : '';
    const headers = { Authorization: `Bearer ${token}`, 'Accept-Language': 'vi-VN' };
    let payload;
    if (body instanceof FormData) payload = body;
    else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await fetch(`${CFG.api}${path}${qs}`, { method, headers, body: payload });
    if (raw) return res;
    const text = await res.text();
    let parsed = text;
    try { parsed = text ? JSON.parse(text) : null; } catch { /* keep text */ }
    if (!res.ok) throw new ApiError(method, path, res.status, parsed);
    return parsed;
  }
  return {
    user,
    get: (p, query) => call('GET', p, undefined, { query }),
    post: (p, b, query) => call('POST', p, b ?? {}, { query }),
    put: (p, b) => call('PUT', p, b),
    del: (p, query) => call('DELETE', p, undefined, { query }),
    upload: (p, fields, file = samplePdf()) => {
      const fd = new FormData();
      fd.append('file', file.blob, file.name);
      for (const [k, v] of Object.entries(fields ?? {})) if (v != null) fd.append(k, String(v));
      return call('POST', p, fd);
    },
  };
}

/** A tiny valid PDF used for every "signed scan" / evidence upload. */
export function samplePdf(name = 'scan.pdf') {
  const pdf = '%PDF-1.7\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF';
  return { name, blob: new Blob([pdf], { type: 'application/pdf' }) };
}

// ---------------------------------------------------------------- Keycloak admin (master realm)

async function kcAdminToken() {
  const res = await fetch(`${CFG.keycloak}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: CFG.kcAdmin, password: CFG.kcAdminPassword }),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`keycloak admin login: ${JSON.stringify(json)}`);
  return json.access_token;
}

/** Creates (or finds) a realm user with a permanent password; returns its id (the token's `sub`). */
export async function ensureKeycloakUser({ username, password, firstName, lastName, email }) {
  const token = await kcAdminToken();
  const base = `${CFG.keycloak}/admin/realms/${CFG.realm}/users`;
  const h = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const find = async () => (await (await fetch(`${base}?exact=true&username=${encodeURIComponent(username)}`, { headers: h })).json())[0];
  let u = await find();
  if (!u) {
    const res = await fetch(base, {
      method: 'POST', headers: h,
      body: JSON.stringify({ username, firstName, lastName, email, enabled: true, emailVerified: true,
        credentials: [{ type: 'password', value: password, temporary: false }] }),
    });
    if (!res.ok) throw new Error(`keycloak create ${username}: ${res.status} ${await res.text()}`);
    u = await find();
  }
  return u.id;
}

// ---------------------------------------------------------------- logging

let failures = 0;
export async function step(name, fn) {
  try {
    const r = await fn();
    console.log(`  ✓ ${name}`);
    return r;
  } catch (e) {
    failures++;
    console.log(`  ✗ ${name}\n      ${e.message}`);
    return undefined;
  }
}
export const section = (t) => console.log(`\n== ${t}`);
export const failureCount = () => failures;

export const iso = (d) => d.toISOString().slice(0, 10);
export function addDays(date, days) {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return iso(d);
}
export function addMonths(date, months) {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCMonth(d.getUTCMonth() + months);
  return iso(d);
}
export const TODAY = iso(new Date(Date.now() + 7 * 3600 * 1000)); // Asia/Ho_Chi_Minh business date
