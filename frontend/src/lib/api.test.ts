import { ApiError, api, http, refreshSession, setSession } from './api';
import { tokenFor } from '@/test/harness';

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });

const session = (token: string) => ({ ...tokenFor(), accessToken: token });

describe('api client', () => {
  afterEach(() => { vi.restoreAllMocks(); setSession(null); });

  it('turns problem+json into ApiError with code, field errors and correlation id', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(422, {
      status: 422, title: 'Unprocessable', detail: 'Validation failed', code: 'validation_failed', correlationId: 'corr-1',
      errors: [{ field: 'email', message: 'must be a well-formed email address' }],
    }));
    const err = await http.get('/api/v1/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    const e = err as ApiError;
    expect(e.status).toBe(422);
    expect(e.code).toBe('validation_failed');
    expect(e.correlationId).toBe('corr-1');
    expect(e.fieldErrors[0]?.field).toBe('email');
    expect(e.message).toBe('Validation failed');
  });

  it('falls back to a readable message and the header correlation id for non-JSON errors', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('bad gateway', { status: 503, headers: { 'X-Correlation-Id': 'corr-2' } }));
    const e = (await http.get('/api/v1/x').catch((x: unknown) => x)) as ApiError;
    expect(e.status).toBe(503);
    expect(e.correlationId).toBe('corr-2');
    expect(e.message).toMatch(/temporarily unavailable/);
  });

  it('sends the bearer token and the CSRF marker header', async () => {
    setSession(session('tok-1'));
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(200, { ok: true }));
    await http.post('/api/v1/y', { a: 1 }, { 'Idempotency-Key': 'k1' });
    const init = spy.mock.calls[0]![1]!;
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer tok-1');
    expect(headers['X-Requested-With']).toBe('marketflow-web');
    expect(headers['Idempotency-Key']).toBe('k1');
    expect(init.body).toBe('{"a":1}');
  });

  it('refreshes once for concurrent 401s and retries each request with the new token', async () => {
    setSession(session('expired'));
    let refreshCalls = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === '/api/v1/auth/refresh') {
        refreshCalls++;
        await new Promise((r) => setTimeout(r, 10));
        return json(200, session('fresh'));
      }
      const auth = (init?.headers as Record<string, string>).Authorization;
      return auth === 'Bearer fresh' ? json(200, { url }) : json(401, { status: 401, code: 'unauthorized' });
    });
    const results = await Promise.all([http.get<{ url: string }>('/a'), http.get<{ url: string }>('/b'), http.get<{ url: string }>('/c')]);
    expect(results.map((r) => r.url)).toEqual(['/a', '/b', '/c']);
    expect(refreshCalls).toBe(1);
  });

  it('clears the session when the refresh cookie is rejected', async () => {
    setSession(session('expired'));
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) =>
      String(input) === '/api/v1/auth/refresh' ? json(401, { status: 401 }) : json(401, { status: 401, detail: 'Sign in again' }));
    const e = (await api('/z').catch((x: unknown) => x)) as ApiError;
    expect(e.status).toBe(401);
    expect(await refreshSession()).toBeNull();
  });

  it('does not attempt a refresh for anonymous requests', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(401, { status: 401 }));
    await api('/private').catch(() => undefined);
    expect(spy).toHaveBeenCalledTimes(1);
  });
});
