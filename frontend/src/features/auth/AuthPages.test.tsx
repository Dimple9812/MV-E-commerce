import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setSession } from '@/lib/api';
import { json, mockApi, renderApp, tokenFor } from '@/test/harness';
import { LoginPage, RegisterPage } from './AuthPages';

describe('sign in', () => {
  afterEach(() => { vi.restoreAllMocks(); setSession(null); });

  it('validates before calling the API', async () => {
    const { calls } = mockApi({ 'POST /api/v1/auth/refresh': () => json(401, { status: 401 }) });
    renderApp(<LoginPage />, { path: '/login', route: '/login' });
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Enter a valid e-mail address')).toBeInTheDocument();
    expect(screen.getByText('Enter your password')).toBeInTheDocument();
    expect(calls.filter((c) => c.key === 'POST /api/v1/auth/login')).toHaveLength(0);
  });

  it('shows the server error for wrong credentials', async () => {
    mockApi({
      'POST /api/v1/auth/refresh': () => json(401, { status: 401 }),
      'POST /api/v1/auth/login': () => json(401, { status: 401, detail: 'Invalid e-mail or password', code: 'invalid_credentials' }),
    });
    renderApp(<LoginPage />, { path: '/login', route: '/login' });
    await userEvent.type(await screen.findByLabelText('E-mail'), 'ada@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password-1');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid e-mail or password');
  });

  it('returns to the requested page after signing in', async () => {
    mockApi({
      'POST /api/v1/auth/refresh': () => json(401, { status: 401 }),
      'POST /api/v1/auth/login': () => json(200, tokenFor()),
    });
    renderApp(<LoginPage />, { path: '/login', route: '/login?next=/checkout' });
    await userEvent.type(await screen.findByLabelText('E-mail'), 'ada@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'CorrectHorse12');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByTestId('location')).toHaveTextContent(/^\/checkout$/);
  });

  it('ignores off-site redirect targets', async () => {
    mockApi({
      'POST /api/v1/auth/refresh': () => json(401, { status: 401 }),
      'POST /api/v1/auth/login': () => json(200, tokenFor()),
    });
    renderApp(<LoginPage />, { path: '/login', route: '/login?next=//evil.example/steal' });
    await userEvent.type(await screen.findByLabelText('E-mail'), 'ada@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'CorrectHorse12');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByTestId('location')).toHaveTextContent(/^\/$/);
  });
});

describe('registration', () => {
  afterEach(() => { vi.restoreAllMocks(); setSession(null); });

  it('enforces the password policy on the client', async () => {
    mockApi({ 'POST /api/v1/auth/refresh': () => json(401, { status: 401 }) });
    renderApp(<RegisterPage />, { path: '/register', route: '/register' });
    await userEvent.type(await screen.findByLabelText('Full name'), 'Ada Moreno');
    await userEvent.type(screen.getByLabelText('E-mail'), 'ada@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'onlyletters');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Include a digit')).toBeInTheDocument();
  });

  it('maps server field errors onto the form', async () => {
    mockApi({
      'POST /api/v1/auth/refresh': () => json(401, { status: 401 }),
      'POST /api/v1/auth/register': () => json(409, { status: 409, detail: 'An account with this e-mail already exists', code: 'email_taken',
        errors: [{ field: 'email', message: 'Already registered' }] }),
    });
    renderApp(<RegisterPage />, { path: '/register', route: '/register' });
    await userEvent.type(await screen.findByLabelText('Full name'), 'Ada Moreno');
    await userEvent.type(screen.getByLabelText('E-mail'), 'ada@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'CorrectHorse12');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Already registered')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('already exists');
  });
});
