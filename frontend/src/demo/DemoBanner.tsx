import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthProvider';
import { DEMO_ACCOUNTS, DEMO_PASSWORD, resetDemo } from './server';

const REPO = 'https://github.com/Dimple9812/MV-E-commerce';
const HOME: Record<string, string> = { Customer: '/', Seller: '/vendor', Admin: '/admin' };

/** Strip above the store header that says what this deployment is and offers one-click demo sign-ins. */
export function DemoBanner() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [busy, setBusy] = useState<string | null>(null);
  const [details, setDetails] = useState(false);

  const signInAs = async (role: string, email: string) => {
    setBusy(role);
    try {
      qc.clear();
      await login(email, DEMO_PASSWORD);
      // Let the route guards react to the new user first (switching from Seller to Admin on a seller page makes the
      // seller guard redirect), then go to the new role's home.
      await new Promise((r) => window.setTimeout(r, 50));
      navigate(HOME[role] ?? '/');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="bg-ink text-mist" role="region" aria-label="About this demo">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2 text-sm sm:px-6">
        <p className="min-w-0 flex-1 basis-72">
          <span className="font-semibold">Live demo.</span>{' '}
          The backend is simulated in your browser with sample data, so changes stay on this device.{' '}
          <button className="underline underline-offset-2 hover:no-underline" onClick={() => setDetails(!details)} aria-expanded={details}>
            {details ? 'Less' : 'How it works'}
          </button>
        </p>
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Sign in as a demo user">
          <span className="text-mist/70">Try it as</span>
          {DEMO_ACCOUNTS.map((a) => (
            <button key={a.role} disabled={!!busy || user?.email === a.email}
              className="rounded-full border border-mist/30 px-3 py-0.5 font-medium hover:bg-mist/10 disabled:opacity-50"
              onClick={() => void signInAs(a.role, a.email)}>
              {busy === a.role ? 'Signing in…' : a.role}
            </button>
          ))}
        </div>
      </div>
      {details && (
        <div className="mx-auto max-w-7xl px-4 pb-3 text-sm text-mist/85 sm:px-6">
          <p className="max-w-3xl">
            The full MarketFlow system is ten Spring Boot services talking over Kafka, with PostgreSQL and Redis. This public
            page runs the real React storefront against an in-browser stand-in for those services, which follows the same
            rules: server-side pricing, one suborder per seller, a checkout saga with compensations (try the declined and
            failing test cards), stock reservations and verified-purchase reviews. Demo password for every account:{' '}
            <code className="rounded bg-mist/10 px-1">{DEMO_PASSWORD}</code>.
          </p>
          <p className="mt-2 flex flex-wrap gap-4">
            <a className="underline underline-offset-2 hover:no-underline" href={REPO} target="_blank" rel="noreferrer">Source code and architecture on GitHub</a>
            <button className="underline underline-offset-2 hover:no-underline" onClick={() => {
              resetDemo();
              try { window.localStorage.removeItem('marketflow.cart.v1'); } catch { /* storage unavailable */ }
              window.location.assign('/');
            }}>Reset the demo data</button>
          </p>
        </div>
      )}
    </div>
  );
}
