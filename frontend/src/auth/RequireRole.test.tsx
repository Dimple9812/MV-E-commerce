import { screen } from '@testing-library/react';
import { setSession } from '@/lib/api';
import { json, mockApi, renderApp, tokenFor } from '@/test/harness';
import { RequireRole } from './RequireRole';

describe('RequireRole', () => {
  afterEach(() => { vi.restoreAllMocks(); setSession(null); });

  it('sends anonymous visitors to sign in and remembers where they were going', async () => {
    mockApi({ 'POST /api/v1/auth/refresh': () => json(401, { status: 401 }) });
    renderApp(<RequireRole><p>secret</p></RequireRole>, { path: '/account/orders', route: '/account/orders?page=2' });
    expect(await screen.findByTestId('location')).toHaveTextContent('/login?next=%2Faccount%2Forders%3Fpage%3D2');
    expect(screen.queryByText('secret')).not.toBeInTheDocument();
  });

  it('sends customers without the seller role to the seller application', async () => {
    mockApi({ 'POST /api/v1/auth/refresh': () => json(200, tokenFor(['CUSTOMER'])) });
    renderApp(<RequireRole role="VENDOR"><p>hub</p></RequireRole>, { path: '/vendor', route: '/vendor' });
    expect(await screen.findByTestId('location')).toHaveTextContent('/sell');
  });

  it('renders the page for the right role', async () => {
    mockApi({ 'POST /api/v1/auth/refresh': () => json(200, tokenFor(['CUSTOMER', 'ADMIN'])) });
    renderApp(<RequireRole role="ADMIN"><p>admin area</p></RequireRole>, { path: '/admin', route: '/admin' });
    expect(await screen.findByText('admin area')).toBeInTheDocument();
  });
});
