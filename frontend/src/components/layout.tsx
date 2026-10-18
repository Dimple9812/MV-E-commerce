import clsx from 'clsx';
import { Bell, LogOut, Menu, MessageCircle, Search, ShoppingBag, Store, User as UserIcon, X } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthProvider';
import { useCart } from '@/cart/CartProvider';
import { http } from '@/lib/api';
import { useUnreadChats } from '@/features/chat/hooks';

export function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <Link to="/" className="flex items-center gap-2" aria-label="MarketFlow home">
      <svg viewBox="0 0 32 32" className="h-8 w-8" aria-hidden>
        <path d="M4 9a3 3 0 0 1 3-3h13l8 10-8 10H7a3 3 0 0 1-3-3z" fill="rgb(var(--tag))" />
        <circle cx="10" cy="16" r="2.6" fill="#17233F" />
      </svg>
      {!compact && <span className="font-display text-xl font-extrabold tracking-tight">MarketFlow</span>}
    </Link>
  );
}

function SearchBox({ className }: { className?: string }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [q, setQ] = useState(params.get('q') ?? '');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    navigate(`/search${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ''}`);
  };
  return (
    <form onSubmit={submit} role="search" className={clsx('relative', className)}>
      <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate" aria-hidden />
      <label htmlFor="site-search" className="sr-only">Search products</label>
      <input id="site-search" value={q} onChange={(e) => setQ(e.target.value)}
        placeholder="Search headphones, skillets, trail shoes…" className="input pl-9" />
    </form>
  );
}

function UnreadBadge() {
  const { user } = useAuth();
  const { data } = useQuery({
    queryKey: ['notifications', 'unread'],
    queryFn: () => http.get<{ unread: number }>('/api/v1/notifications/unread-count'),
    enabled: !!user,
    refetchInterval: 30_000,
  });
  if (!data?.unread) return null;
  return <span className="absolute -right-1 -top-1 min-w-4 rounded-full bg-brick px-1 text-center text-[10px] font-bold leading-4 text-white">{data.unread}</span>;
}

function ChatBadge() {
  const unread = useUnreadChats().data?.asBuyer ?? 0;
  if (!unread) return null;
  return <span className="absolute -right-1 -top-1 min-w-4 rounded-full bg-brick px-1 text-center text-[10px] font-bold leading-4 text-white">{unread > 99 ? '99+' : unread}</span>;
}

export function StoreLayout() {
  const { user, hasRole, logout } = useAuth();
  const { count } = useCart();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  return (
    <div className="flex min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-surface focus:px-3 focus:py-2">Skip to content</a>
      <header className="sticky top-0 z-30 border-b border-line bg-mist/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center gap-4 px-4 py-3 sm:px-6">
          <Logo />
          <SearchBox className="hidden flex-1 md:block md:max-w-xl" />
          <nav className="ml-auto flex items-center gap-1" aria-label="Main">
            <NavLink to={hasRole('VENDOR') ? '/vendor' : '/sell'} className="btn hidden px-3 text-slate hover:text-ink sm:inline-flex">
              <Store className="h-4 w-4" aria-hidden /> {hasRole('VENDOR') ? 'Seller hub' : 'Sell on MarketFlow'}
            </NavLink>
            {hasRole('ADMIN') && (
              <NavLink to="/admin" className="btn hidden px-3 text-slate hover:text-ink sm:inline-flex">Admin</NavLink>
            )}
            {user && (
              <Link to="/account/messages" className="btn relative px-3" aria-label="Messages">
                <MessageCircle className="h-5 w-5" aria-hidden /><ChatBadge />
              </Link>
            )}
            {user && (
              <Link to="/account/notifications" className="btn relative px-3" aria-label="Notifications">
                <Bell className="h-5 w-5" aria-hidden /><UnreadBadge />
              </Link>
            )}
            <Link to="/cart" className="btn relative px-3" aria-label={`Cart, ${count} item${count === 1 ? '' : 's'}`}>
              <ShoppingBag className="h-5 w-5" aria-hidden />
              {count > 0 && <span className="absolute -right-0.5 -top-0.5 min-w-4 rounded-full bg-tag px-1 text-center text-[10px] font-bold leading-4 text-[#17233F]">{count}</span>}
            </Link>
            {user ? (
              <div className="hidden items-center gap-1 sm:flex">
                <Link to="/account" className="btn px-3"><UserIcon className="h-4 w-4" aria-hidden />{user.fullName.split(' ')[0]}</Link>
                <button className="btn px-3 text-slate" onClick={async () => { await logout(); navigate('/'); }} aria-label="Sign out">
                  <LogOut className="h-4 w-4" aria-hidden />
                </button>
              </div>
            ) : (
              <Link to="/login" className="btn-primary ml-1 hidden sm:inline-flex">Sign in</Link>
            )}
            <button className="btn px-2 sm:hidden" onClick={() => setOpen(!open)} aria-expanded={open} aria-label="Menu">
              {open ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
            </button>
          </nav>
        </div>
        <div className="px-4 pb-3 md:hidden"><SearchBox /></div>
        {open && (
          <div className="border-t border-line px-4 py-3 sm:hidden" onClick={() => setOpen(false)}>
            <div className="flex flex-col gap-1">
              <Link className="btn justify-start" to={hasRole('VENDOR') ? '/vendor' : '/sell'}>{hasRole('VENDOR') ? 'Seller hub' : 'Sell on MarketFlow'}</Link>
              {hasRole('ADMIN') && <Link className="btn justify-start" to="/admin">Admin</Link>}
              {user ? (
                <>
                  <Link className="btn justify-start" to="/account">Account</Link>
                  <Link className="btn justify-start" to="/account/messages">Messages</Link>
                  <button className="btn justify-start" onClick={() => void logout()}>Sign out</button>
                </>
              ) : <Link className="btn justify-start" to="/login">Sign in</Link>}
            </div>
          </div>
        )}
      </header>
      <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6"><Outlet /></main>
      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-4 py-8 text-sm text-slate sm:px-6">
          <div className="flex items-center gap-3"><Logo compact /> <span>Independent sellers, one checkout.</span></div>
          <div className="flex gap-5">
            <Link to="/search">Browse</Link>
            <Link to="/sell">Become a seller</Link>
            <span title="Payments use a local sandbox provider; no real money moves">Sandbox payments</span>
          </div>
        </div>
      </footer>
    </div>
  );
}

export interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
  /** e.g. an unread count */
  badge?: ReactNode;
}

export function DashboardLayout({ title, items }: { title: string; items: NavItem[] }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[15rem_1fr]">
      <aside className="border-b border-line bg-surface lg:sticky lg:top-0 lg:h-screen lg:border-b-0 lg:border-r">
        <div className="flex items-center justify-between px-5 py-4">
          <Logo />
        </div>
        <div className="px-5 pb-2 text-sm font-semibold text-slate">{title}</div>
        <nav className="flex gap-1 overflow-x-auto px-3 pb-3 lg:flex-col lg:overflow-visible" aria-label={title}>
          {items.map((i) => (
            <NavLink key={i.to} to={i.to} end={i.end}
              className={({ isActive }) => clsx('flex shrink-0 items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium',
                isActive ? 'bg-ink text-mist' : 'text-ink/80 hover:bg-mist')}>
              {i.icon}{i.label}{i.badge}
            </NavLink>
          ))}
        </nav>
        <div className="hidden border-t border-line px-5 py-4 text-sm lg:absolute lg:bottom-0 lg:block lg:w-60">
          <div className="truncate font-medium">{user?.fullName}</div>
          <div className="truncate text-slate">{user?.email}</div>
          <div className="mt-3 flex gap-3">
            <Link to="/" className="text-slate hover:text-ink">Storefront</Link>
            <button className="text-slate hover:text-ink" onClick={async () => { await logout(); navigate('/'); }}>Sign out</button>
          </div>
        </div>
      </aside>
      <main id="main" className="min-w-0 px-4 py-8 sm:px-8"><Outlet /></main>
    </div>
  );
}
