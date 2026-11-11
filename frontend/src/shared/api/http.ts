/**
 * Thin fetch wrapper for the YourHRM API behind the BFF (ADR-0014): same-origin, cookies only, no token in
 * JavaScript. Errors arrive as RFC 9457 problem details (`application/problem+json`) with a `code` property
 * (`ProblemDetailsExceptionHandler`); they are thrown as {@link ApiProblem} so callers can branch on `code`.
 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  /** Stable machine code, e.g. `stale-version`, `validation-failed`, `unknown-user`. */
  code: string;
  /** `validation-failed` carries one entry per invalid field. */
  errors?: { field: string; message: string }[];
  [extension: string]: unknown;
}

export class ApiProblem extends Error {
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.name = 'ApiProblem';
    this.problem = problem;
  }

  get status() {
    return this.problem.status;
  }

  get code() {
    return this.problem.code;
  }
}

export type QueryParams = Record<string, string | number | boolean | null | undefined | string[]>;

export interface RequestOptions {
  params?: QueryParams;
  /** JSON body (objects) or a FormData for multipart uploads. */
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** `Idempotency-Key` for POSTs that create side effects (ADR-0014 decision 3). */
  idempotencyKey?: string;
}

export const API_BASE = '/api/v1';

/** Listeners told about a 401 (session expired): the auth layer redirects to the login page. */
const unauthenticatedListeners = new Set<() => void>();
export function onUnauthenticated(listener: () => void): () => void {
  unauthenticatedListeners.add(listener);
  return () => unauthenticatedListeners.delete(listener);
}

export function buildQuery(params?: QueryParams): string {
  if (!params) return '';
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) value.forEach((v) => search.append(key, v));
    else search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : '';
}

async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json', ...options.headers };
  let body: BodyInit | undefined;
  if (options.body instanceof FormData) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  const url = path.startsWith('/api') ? path : `${API_BASE}${path}`;
  const response = await fetch(url + buildQuery(options.params), {
    method,
    headers,
    body,
    credentials: 'same-origin',
    signal: options.signal,
  });

  if (response.status === 204 || response.headers.get('content-length') === '0') {
    if (!response.ok) throw await toProblem(response);
    return undefined as T;
  }
  if (!response.ok) throw await toProblem(response);

  const contentType = response.headers.get('content-type') ?? '';
  if (contentType.includes('json')) return (await response.json()) as T;
  return (await response.blob()) as T;
}

async function toProblem(response: Response): Promise<ApiProblem> {
  let problem: ProblemDetail;
  try {
    const parsed = (await response.json()) as Partial<ProblemDetail>;
    problem = { status: response.status, code: parsed.code ?? `http-${response.status}`, ...parsed } as ProblemDetail;
  } catch {
    problem = { status: response.status, code: `http-${response.status}`, title: response.statusText };
  }
  if (response.status === 401) unauthenticatedListeners.forEach((l) => l());
  return new ApiProblem(problem);
}

export const http = {
  get: <T>(path: string, options?: RequestOptions) => request<T>('GET', path, options),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>('POST', path, { ...options, body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('PUT', path, { ...options, body }),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>('PATCH', path, { ...options, body }),
  delete: <T = void>(path: string, options?: RequestOptions) => request<T>('DELETE', path, options),
  /** Binary download (Excel templates, error files): returns the Blob and the suggested file name. */
  download: async (path: string, options?: RequestOptions): Promise<{ blob: Blob; fileName?: string }> => {
    const url = path.startsWith('/api') ? path : `${API_BASE}${path}`;
    const response = await fetch(url + buildQuery(options?.params), {
      credentials: 'same-origin',
      signal: options?.signal,
    });
    if (!response.ok) throw await toProblem(response);
    const disposition = response.headers.get('content-disposition') ?? '';
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
    return { blob: await response.blob(), fileName: match?.[1] ? decodeURIComponent(match[1]) : undefined };
  },
};

/** Saves a downloaded blob through a temporary anchor. */
export function saveBlob(blob: Blob, fileName: string) {
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(href);
}

export function isProblem(error: unknown, code?: string): error is ApiProblem {
  return error instanceof ApiProblem && (code === undefined || error.code === code);
}
