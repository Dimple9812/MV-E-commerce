import clsx from 'clsx';
import { AlertCircle, Check, CheckCheck, Clock, ImageOff, Package, WifiOff } from 'lucide-react';
import { Link } from 'react-router-dom';
import { ProductArt, Spinner } from '@/components/ui';
import { humanize, initials, money, relative, replyTime, shortId } from '@/lib/format';
import type { ChatContext, ChatCounterpart, ChatMessage } from '@/lib/types';
import { usePresence } from './ChatProvider';
import { useAuthedImage } from './hooks';
import type { ConnectionState } from './transport';

export function OnlineDot({ online, className }: { online: boolean; className?: string }) {
  return (
    <span className={clsx('inline-block h-2.5 w-2.5 rounded-full ring-2 ring-surface', online ? 'bg-leaf' : 'bg-line', className)}
      aria-hidden />
  );
}

export function ChatAvatar({ name, online, shop }: { name: string; online?: boolean; shop?: boolean }) {
  return (
    <span className="relative inline-flex shrink-0">
      <span className={clsx('flex h-10 w-10 items-center justify-center font-display text-sm font-extrabold',
        shop ? 'rounded-tag bg-tag text-[#17233F]' : 'rounded-full bg-ink/10 text-ink')}>
        {initials(name) || '?'}
      </span>
      {online !== undefined && <OnlineDot online={online} className="absolute -bottom-0.5 -right-0.5" />}
    </span>
  );
}

/** "Online", "Last seen 5 min ago", plus the shop's typical reply time. */
export function PresenceLine({ counterpart }: { counterpart: ChatCounterpart }) {
  const p = usePresence(counterpart.userIds, counterpart);
  const reply = counterpart.kind === 'SHOP' ? replyTime(counterpart.typicalReplySeconds) : null;
  return (
    <span className="text-xs text-slate">
      {p.online ? <span className="font-medium text-leaf">Online</span>
        : p.lastSeenAt ? `Last seen ${relative(p.lastSeenAt)}` : 'Offline'}
      {reply && <> · {reply}</>}
    </span>
  );
}

/** What the conversation is about, under the header. */
export function ContextBar({ context, orderLink }: { context: ChatContext; orderLink?: string | null }) {
  if (context.kind === 'GENERAL') return null;
  if (context.kind === 'PRODUCT') {
    return (
      <Link to={`/products/${context.productId}`} className="flex items-center gap-3 border-b border-line bg-mist/60 px-4 py-2 text-sm hover:bg-mist">
        <span className="h-10 w-10 shrink-0 overflow-hidden rounded-md border border-line"><ProductArt name={context.productName ?? 'Product'} url={context.productImageUrl} /></span>
        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium">{context.productName}</span>
          {context.price != null && <span className="text-slate">from {money(context.price, context.currency ?? 'USD')}</span>}
        </span>
      </Link>
    );
  }
  const body = (
    <>
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md border border-line bg-surface"><Package className="h-5 w-5 text-slate" aria-hidden /></span>
      <span className="min-w-0 flex-1">
        <span className="block truncate font-medium">Order {context.orderId ? shortId(context.orderId) : ''} · {humanize(context.orderStatus)}</span>
        <span className="block truncate text-slate">{context.itemsSummary}{context.orderTotal != null && ` · ${money(context.orderTotal, context.currency ?? 'USD')}`}</span>
      </span>
    </>
  );
  return orderLink
    ? <Link to={orderLink} className="flex items-center gap-3 border-b border-line bg-mist/60 px-4 py-2 text-sm hover:bg-mist">{body}</Link>
    : <div className="flex items-center gap-3 border-b border-line bg-mist/60 px-4 py-2 text-sm">{body}</div>;
}

export function ConnectionBanner({ state }: { state: ConnectionState }) {
  if (state !== 'reconnecting') return null;
  return (
    <div role="status" className="flex items-center gap-2 border-b border-tag-deep/30 bg-tag/15 px-4 py-2 text-sm">
      <WifiOff className="h-4 w-4" aria-hidden /> Reconnecting… messages you send now are delivered when the connection is back.
    </div>
  );
}

export function Ticks({ seen }: { seen: boolean }) {
  return seen
    ? <span className="inline-flex items-center gap-0.5" title="Seen"><CheckCheck className="h-3.5 w-3.5" aria-hidden /><span className="sr-only">Seen</span></span>
    : <span className="inline-flex items-center gap-0.5" title="Sent"><Check className="h-3.5 w-3.5" aria-hidden /><span className="sr-only">Sent</span></span>;
}

export function PendingTicks({ failed }: { failed: boolean }) {
  return failed
    ? <span className="inline-flex items-center gap-1 text-brick"><AlertCircle className="h-3.5 w-3.5" aria-hidden />Not sent</span>
    : <span className="inline-flex items-center gap-1" title="Sending"><Clock className="h-3.5 w-3.5" aria-hidden /><span className="sr-only">Sending</span></span>;
}

export function TypingIndicator({ name }: { name: string }) {
  return (
    <div className="flex items-center gap-2 px-4 pb-2 text-xs text-slate" role="status" aria-live="polite">
      <span className="flex gap-0.5" aria-hidden>
        {[0, 1, 2].map((i) => <span key={i} className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate/70" style={{ animationDelay: `${i * 120}ms` }} />)}
      </span>
      {name} is typing…
    </div>
  );
}

export function ChatImage({ url, alt }: { url: string; alt: string }) {
  const { src, failed } = useAuthedImage(url);
  if (failed) return <span className="flex h-32 w-48 items-center justify-center gap-2 rounded-lg bg-mist text-xs text-slate"><ImageOff className="h-4 w-4" aria-hidden />Image unavailable</span>;
  if (!src) return <span className="flex h-32 w-48 items-center justify-center rounded-lg bg-mist"><Spinner /></span>;
  return (
    <a href={src} target="_blank" rel="noreferrer" className="block">
      <img src={src} alt={alt} className="max-h-72 w-auto max-w-full rounded-lg object-contain" />
    </a>
  );
}

/** Card bubbles are built server-side from the catalog and order projections, never from what the client sent. */
export function CardBubble({ message, orderLink }: { message: ChatMessage; orderLink?: (orderId: string) => string | null }) {
  const m = (message.metadata ?? {}) as Record<string, string | number | null>;
  if (message.type === 'PRODUCT_CARD') {
    return (
      <Link to={`/products/${m.productId}`} className="flex w-64 max-w-full items-center gap-3 rounded-lg border border-line bg-surface p-2 text-ink hover:border-ink/40">
        <span className="h-14 w-14 shrink-0 overflow-hidden rounded-md"><ProductArt name={String(m.name ?? 'Product')} url={(m.imageUrl as string) ?? null} /></span>
        <span className="min-w-0 text-sm">
          <span className="block truncate font-semibold">{m.name}</span>
          {m.price != null && <span className="text-slate">from {money(m.price, String(m.currency ?? 'USD'))}</span>}
        </span>
      </Link>
    );
  }
  const link = orderLink && m.orderId ? orderLink(String(m.orderId)) : null;
  const inner = (
    <>
      <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-md bg-mist"><Package className="h-6 w-6 text-slate" aria-hidden /></span>
      <span className="min-w-0 text-sm">
        <span className="block truncate font-semibold">Order {m.orderId ? shortId(String(m.orderId)) : ''}</span>
        <span className="block truncate text-slate">{m.itemsSummary}</span>
        <span className="text-slate">{humanize(String(m.status ?? ''))}{m.total != null && ` · ${money(m.total, String(m.currency ?? 'USD'))}`}</span>
      </span>
    </>
  );
  return link
    ? <Link to={link} className="flex w-64 max-w-full items-center gap-3 rounded-lg border border-line bg-surface p-2 text-ink hover:border-ink/40">{inner}</Link>
    : <div className="flex w-64 max-w-full items-center gap-3 rounded-lg border border-line bg-surface p-2 text-ink">{inner}</div>;
}

export function DaySeparator({ iso }: { iso: string }) {
  const d = new Date(iso);
  const today = new Date();
  const yesterday = new Date(Date.now() - 86_400_000);
  const label = d.toDateString() === today.toDateString() ? 'Today'
    : d.toDateString() === yesterday.toDateString() ? 'Yesterday'
      : d.toLocaleDateString('en-US', { weekday: 'long', month: 'short', day: 'numeric' });
  return (
    <div className="my-3 flex items-center gap-3 text-xs text-slate" role="separator" aria-label={label}>
      <span className="h-px flex-1 bg-line" /> {label} <span className="h-px flex-1 bg-line" />
    </div>
  );
}

