import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, onSessionChange, refreshSession, setSession, type TokenResponse } from '@/lib/api';
import type { Role, User } from '@/lib/types';

interface AuthState {
  user: User | null;
  /** true until the initial silent refresh finished */
  loading: boolean;
  hasRole: (r: Role) => boolean;
  login: (email: string, password: string) => Promise<User>;
  register: (email: string, password: string, fullName: string) => Promise<User>;
  logout: () => Promise<void>;
  /** re-issues the token, e.g. to pick up a newly granted VENDOR role */
  reload: () => Promise<User | null>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => onSessionChange((s) => setUser(s?.user ?? null)), []);

  // restore the session from the HttpOnly refresh cookie on page load
  useEffect(() => {
    refreshSession().finally(() => setLoading(false));
  }, []);

  // refresh shortly before the 15-minute access token expires
  useEffect(() => {
    if (!user) return;
    const id = window.setInterval(() => void refreshSession(), 12 * 60 * 1000);
    return () => window.clearInterval(id);
  }, [user]);

  const login = useCallback(async (email: string, password: string) => {
    const s = await api<TokenResponse>('/api/v1/auth/login', { method: 'POST', body: { email, password }, noRetry: true });
    setSession(s);
    return s.user;
  }, []);

  const register = useCallback(async (email: string, password: string, fullName: string) => {
    const s = await api<TokenResponse>('/api/v1/auth/register', {
      method: 'POST', body: { email, password, fullName }, noRetry: true,
    });
    setSession(s);
    return s.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await api('/api/v1/auth/logout', { method: 'POST', body: {}, noRetry: true });
    } finally {
      setSession(null);
    }
  }, []);

  const reload = useCallback(async () => (await refreshSession())?.user ?? null, []);

  const value = useMemo<AuthState>(() => ({
    user, loading, login, register, logout, reload,
    hasRole: (r) => !!user?.roles.includes(r),
  }), [user, loading, login, register, logout, reload]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
