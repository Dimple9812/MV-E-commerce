import clsx from 'clsx';
import { BellOff } from 'lucide-react';
import { Link } from 'react-router-dom';
import { ErrorState, Skeleton } from '@/components/ui';
import { chatTime } from '@/lib/format';
import type { ChatSide, ConversationSummary } from '@/lib/types';
import { useChat, usePresence } from './ChatProvider';
import { useInbox } from './hooks';
import { contextLabel } from './labels';
import { ChatAvatar } from './parts';

export function ConversationList({ side, activeId, linkTo, emptyText }: {
  side: ChatSide; activeId?: string; linkTo: (id: string) => string; emptyText: string;
}) {
  const inbox = useInbox(side);
  const items = inbox.data?.pages.flatMap((p) => p.items) ?? [];
  if (inbox.isPending) {
    return <div className="flex flex-col gap-2 p-3">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-16" />)}</div>;
  }
  if (inbox.isError) return <div className="p-3"><ErrorState error={inbox.error} onRetry={() => inbox.refetch()} /></div>;
  if (items.length === 0) return <p className="p-6 text-sm text-slate">{emptyText}</p>;
  return (
    <nav aria-label="Conversations" className="min-h-0 overflow-y-auto">
      <ul className="divide-y divide-line">
        {items.map((c) => <li key={c.id}><Row c={c} side={side} active={c.id === activeId} to={linkTo(c.id)} /></li>)}
      </ul>
      {inbox.hasNextPage && (
        <div className="p-3">
          <button className="btn-quiet w-full" disabled={inbox.isFetchingNextPage} onClick={() => void inbox.fetchNextPage()}>
            {inbox.isFetchingNextPage ? 'Loading…' : 'Show older conversations'}
          </button>
        </div>
      )}
    </nav>
  );
}

function Row({ c, side, active, to }: { c: ConversationSummary; side: ChatSide; active: boolean; to: string }) {
  const presence = usePresence(c.counterpart.userIds, c.counterpart);
  const { typing } = useChat();
  const isTyping = typing[c.id] && typing[c.id]!.side !== side;
  const context = contextLabel(c.context);
  const mine = c.lastMessage?.senderRole === side;
  return (
    <Link to={to} aria-current={active ? 'page' : undefined}
      className={clsx('flex items-start gap-3 px-4 py-3 hover:bg-mist', active && 'bg-mist')}>
      <ChatAvatar name={c.counterpart.name} online={presence.online} shop={c.counterpart.kind === 'SHOP'} />
      <span className="min-w-0 flex-1">
        <span className="flex items-baseline justify-between gap-2">
          <span className={clsx('truncate', c.unread > 0 ? 'font-bold' : 'font-medium')}>{c.counterpart.name}</span>
          <time className="shrink-0 text-xs text-slate" dateTime={c.lastMessageAt}>{chatTime(c.lastMessageAt)}</time>
        </span>
        {context && <span className="block truncate text-xs text-slate">{context}</span>}
        <span className="flex items-center justify-between gap-2">
          <span className={clsx('truncate text-sm', c.unread > 0 ? 'text-ink' : 'text-slate', isTyping && 'italic text-leaf')}>
            {isTyping ? 'typing…' : c.lastMessage ? `${mine ? 'You: ' : ''}${c.lastMessage.text}` : ''}
          </span>
          <span className="flex shrink-0 items-center gap-1">
            {c.muted && <BellOff className="h-3.5 w-3.5 text-slate" aria-label="Muted" />}
            {c.unread > 0 && (
              <span className="min-w-5 rounded-full bg-brick px-1.5 text-center text-[11px] font-bold leading-5 text-white">
                {c.unread > 99 ? '99+' : c.unread}<span className="sr-only"> unread</span>
              </span>
            )}
          </span>
        </span>
      </span>
    </Link>
  );
}
