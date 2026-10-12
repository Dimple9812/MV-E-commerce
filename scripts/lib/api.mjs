// Minimal API client for MarketFlow scripts (Node 20+, no dependencies).
// All calls go through the gateway exactly like the browser does.

export const BASE = process.env.MF_API ?? 'http://127.0.0.1:8080'   // 127.0.0.1, not localhost: on Windows localhost may resolve to ::1, which Compose does not bind;

export class ApiError extends Error {
  constructor(status, body, method, path) {
    super(`${method} ${path} -> ${status}: ${typeof body === 'string' ? body : JSON.stringify(body)}`);
    this.status = status;
    this.body = body;
  }
}

/** A logged-in session that keeps the refresh cookie, like a browser would. */
export class Session {
  constructor(label) {
    this.label = label;
    this.token = null;
    this.cookie = null;
    this.user = null;
  }

  async call(method, path, body, { headers = {}, expect } = {}) {
    const h = { 'X-Requested-With': 'marketflow-script', ...headers };
    if (body !== undefined) h['Content-Type'] = 'application/json';
    if (this.token) h.Authorization = `Bearer ${this.token}`;
    if (this.cookie) h.Cookie = this.cookie;
    let res;
    // 429 comes from the gateway's rate limiter before the request reaches a service, so retrying is safe even
    // for POSTs. Honour Retry-After, otherwise back off 0.5 s, 1 s, 2 s ...
    // 503 (service still starting, or its circuit breaker open) is retried only for reads and the auth endpoints,
    // which are safe to repeat (register answers 409 the second time, which the caller handles); up to ~3 minutes,
    // because on a slow machine a service can be up but not yet answering in time.
    const retry503 = method === 'GET' || path.startsWith('/api/v1/auth/');
    for (let attempt = 0, unavailable = 0; ; attempt++) {
      res = await fetch(BASE + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
      if (res.status === 503 && retry503 && unavailable < 20) {
        if (unavailable++ === 0) log(`${method} ${path}: service unavailable, retrying for up to 3 minutes ...`);
        await res.text();
        await sleep(Math.min(10000, 1000 * 2 ** Math.min(unavailable, 4)));
        continue;
      }
      if (res.status !== 429 || attempt >= 6) break;
      const after = Number(res.headers.get('retry-after'));
      await sleep(after > 0 ? after * 1000 : 500 * 2 ** attempt);
    }
    const setCookie = res.headers.get('set-cookie');
    if (setCookie && setCookie.startsWith('mf_refresh=')) this.cookie = setCookie.split(';')[0];
    const text = await res.text();
    let data = text;
    try { data = text ? JSON.parse(text) : null; } catch { /* plain text */ }
    if (expect ? !expect.includes(res.status) : !res.ok) throw new ApiError(res.status, data, method, path);
    return { status: res.status, data, headers: res.headers };
  }

  get(p, o) { return this.call('GET', p, undefined, o); }
  post(p, b, o) { return this.call('POST', p, b ?? {}, o); }
  patch(p, b, o) { return this.call('PATCH', p, b, o); }
  put(p, b, o) { return this.call('PUT', p, b, o); }

  adopt(r) {
    this.token = r.data.accessToken;
    this.user = r.data.user;
    return this;
  }

  async register(email, password, fullName) {
    const r = await this.call('POST', '/api/v1/auth/register', { email, password, fullName }, { expect: [201, 409] });
    if (r.status === 409) return this.login(email, password);
    return this.adopt(r);
  }

  async login(email, password) {
    return this.adopt(await this.post('/api/v1/auth/login', { email, password }));
  }

  /** Rotates the refresh cookie; roles granted meanwhile (e.g. VENDOR) appear in the new token. */
  async refresh() {
    return this.adopt(await this.post('/api/v1/auth/refresh'));
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function until(what, fn, { timeoutMs = 30000, intervalMs = 500 } = {}) {
  const end = Date.now() + timeoutMs;
  let last;
  while (Date.now() < end) {
    try {
      last = await fn();
      if (last) return last;
    } catch (e) {
      last = e;
    }
    await sleep(intervalMs);
  }
  throw new Error(`Timed out waiting for: ${what} (last: ${last instanceof Error ? last.message : JSON.stringify(last)})`);
}

export function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}
