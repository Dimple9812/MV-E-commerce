/**
 * Fetch-based API client.
 *
 * - The access token lives only in memory (never localStorage), so XSS cannot lift a long-lived credential.
 * - The refresh token is an HttpOnly cookie the browser sends to /api/v1/auth only; on a 401 the client performs
 *   ONE shared refresh (single-flight) and retries the request once.
 * - Errors are normalised from RFC 9457 problem+json into ApiError with the server's correlation ID.
 */

export interface Problem {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  code?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(p: Problem) {
    super(p.detail ?? p.title ?? `Request failed (${p.status})`);
    this.status = p.status;
    this.code = p.code ?? 'error';
    this.correlationId = p.correlationId;
    this.fieldErrors = p.errors ?? [];
  }
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: import('./types').User;
}

type Listener = (session: TokenResponse | null) => void;

let accessToken: string | null = null;
let refreshing: Promise<TokenResponse | null> | null = null;
const listeners = new Set<Listener>();

export function onSessionChange(l: Listener): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function setSession(s: TokenResponse | null) {
  accessToken = s?.accessToken ?? null;
  listeners.forEach((l) => l(s));
}

const JSON_HEADERS = { 'Content-Type': 'application/json' };

async function parse(res: Response): Promise<unknown> {
  if (res.status === 204) return null;
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

async function toError(res: Response): Promise<ApiError> {
  const body = await parse(res);
  if (body && typeof body === 'object' && 'status' in body) return new ApiError(body as Problem);
  return new ApiError({
    status: res.status,
    detail: res.status === 503 ? 'The service is temporarily unavailable. Try again shortly.' : `Request failed (${res.status})`,
    correlationId: res.headers.get('X-Correlation-Id') ?? undefined,
  });
}

/** Exchanges the refresh cookie for a new access token. Concurrent callers share one request. */
export function refreshSession(): Promise<TokenResponse | null> {
  if (!refreshing) {
    refreshing = fetch('/api/v1/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'X-Requested-With': 'marketflow-web' },
    })
      .then(async (res) => {
        if (!res.ok) {
          setSession(null);
          return null;
        }
        const s = (await res.json()) as TokenResponse;
        setSession(s);
        return s;
      })
      .catch(() => {
        setSession(null);
        return null;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** do not attempt refresh on 401 (used by auth endpoints themselves) */
  noRetry?: boolean;
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const doFetch = () => {
    const headers: Record<string, string> = { 'X-Requested-With': 'marketflow-web', ...opts.headers };
    let body: BodyInit | undefined;
    if (opts.body instanceof FormData) {
      body = opts.body;
    } else if (opts.body !== undefined) {
      Object.assign(headers, JSON_HEADERS);
      body = JSON.stringify(opts.body);
    }
    if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
    return fetch(path, { method: opts.method ?? 'GET', headers, body, credentials: 'same-origin', signal: opts.signal });
  };

  let res = await doFetch();
  if (res.status === 401 && !opts.noRetry && accessToken) {
    const s = await refreshSession();
    if (s) res = await doFetch();
  }
  if (!res.ok) throw await toError(res);
  return (await parse(res)) as T;
}

export const http = {
  get: <T>(p: string, signal?: AbortSignal) => api<T>(p, { signal }),
  post: <T>(p: string, body?: unknown, headers?: Record<string, string>) =>
    api<T>(p, { method: 'POST', body: body ?? {}, headers }),
  put: <T>(p: string, body: unknown) => api<T>(p, { method: 'PUT', body }),
  patch: <T>(p: string, body: unknown) => api<T>(p, { method: 'PATCH', body }),
  del: <T>(p: string) => api<T>(p, { method: 'DELETE' }),
};

export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const sp = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') sp.set(k, String(v));
  });
  const s = sp.toString();
  return s ? `?${s}` : '';
}
