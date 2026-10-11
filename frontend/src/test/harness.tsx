import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider } from '@/auth/AuthProvider';
import { CartProvider } from '@/cart/CartProvider';
import { ToastProvider } from '@/components/Toast';
import type { Role } from '@/lib/types';

type Handler = (init: RequestInit | undefined, url: string) => Response | Promise<Response>;

export const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

export const tokenFor = (roles: Role[] = ['CUSTOMER']) => ({
  accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 900,
  user: { id: '00000000-0000-0000-0000-0000000000a1', email: 'ada@example.com', fullName: 'Ada Moreno', roles, status: 'ACTIVE' as const, createdAt: '2026-01-01T00:00:00Z' },
});

/** Routes fetch calls by "METHOD path" (query string ignored); unknown calls fail the test loudly. */
export function mockApi(routes: Record<string, Handler>) {
  const calls: { key: string; init?: RequestInit }[] = [];
  const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input);
    const key = `${init?.method ?? 'GET'} ${url.split('?')[0]}`;
    calls.push({ key, init });
    const h = routes[key];
    if (!h) throw new Error(`Unexpected request ${key}`);
    return h(init, url);
  });
  return { spy, calls };
}

export function LocationProbe() {
  const l = useLocation();
  return <div data-testid="location">{l.pathname + l.search}</div>;
}

export function renderApp(ui: ReactElement, { path = '/', route = '/' }: { path?: string; route?: string } = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[route]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <AuthProvider>
          <CartProvider>
            <ToastProvider>
              <Routes>
                <Route path={path} element={ui} />
                <Route path="*" element={<LocationProbe />} />
              </Routes>
            </ToastProvider>
          </CartProvider>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
