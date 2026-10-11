import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from './AuthProvider';
import type { Role } from '@/lib/types';
import { PageSpinner } from '@/components/ui';

/**
 * Route guard for usability only: it keeps people out of screens they cannot use. Every API behind these screens
 * enforces the same rules (and ownership) on the server, so bypassing this guard yields 401/403/404, nothing more.
 */
export function RequireRole({ role, children }: { role?: Role; children: ReactNode }) {
  const { user, loading, hasRole } = useAuth();
  const location = useLocation();
  if (loading) return <PageSpinner />;
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  if (role && !hasRole(role)) return <Navigate to={role === 'VENDOR' ? '/sell' : '/'} replace />;
  return <>{children}</>;
}
