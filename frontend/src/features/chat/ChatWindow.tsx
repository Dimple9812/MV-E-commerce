import clsx from 'clsx';
import { ArrowLeft, BellOff, Bell, Flag, ImagePlus, MessageSquareText, MoreHorizontal, Pencil, Send, Trash2, X } from 'lucide-react';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useToast } from '@/components/Toast';
import { Dialog, ErrorState, Field, PageSpinner, Select, Spinner, Textarea } from '@/components/ui';
import { ApiError } from '@/lib/api';
import { chatTime } from '@/lib/format';
import type { ChatMessage, Conversation } from '@/lib/types';
import { chatApi, chatKeys, flatten, upsertMessages } from './cache';
import { useChat, usePresence, type PendingMessage } from './ChatProvider';
import { useConversation, useMessages, useQuickReplies } from './hooks';
import {
  CardBubble, ChatAvatar, ChatImage, ConnectionBanner, ContextBar, DaySeparator, PendingTicks, PresenceLine, Ticks,
  TypingIndicator,
} from './parts';

export const MAX_LENGTH = 2000;
const EDIT_WINDOW_MS = 10 * 60 * 1000;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp'];

export interface Attachment { type: 'PRODUCT_CARD' | 'ORDER_CARD'; productId?: string; suborderId?: string; label: string }

interface Props {
  conversationId: string;
  /** mobile: back to the list */
  backTo?: string;
  /** a product or order card to send with the first message (from "Message seller") */
  attachment?: Attachment | null;
  onAttachmentUsed?: () => void;
  /** where an order card links to for this viewer */
  orderLink?: (orderId: string) => string | null;
}

export function ChatWindow({ conversationId, backTo, attachment, onAttachmentUsed, orderLink }: Props) {
  const conversation = useConversation(conversationId);
  const messages = useMessages(conversationId);
  const chat = useChat();

  if (conversation.isPending || messages.isPending) return <div className="flex h-full items-center justify-center"><PageSpinner /></div>;
  if (conversation.isError || !conversation.data) {
    return <div className="p-4"><ErrorState title="This conversation is not available" error={conversation.error} onRetry={() => conversation.refetch()} /></div>;
  }
  const c = conversation.data;
  return (
    <section className="flex h-full min-h-0 flex-col" aria-label={`Conversation with ${c.counterpart.name}`}>
      <Header conversation={c} backTo={backTo} />
      <ConnectionBanner state={chat.connection} />
      <ContextBar context={c.context} orderLink={c.context.orderId && orderLink ? orderLink(c.context.orderId) : null} />
      {c.status === 'BLOCKED' && (
        <p className="border-b border-brick/30 bg-brick/10 px-4 py-2 text-sm">This conversation was closed by MarketFlow support. You can still read it.</p>
      )}
      <Timeline conversation={c} messages={messages} orderLink={orderLink} />
      {chat.typing[c.id] && chat.typing[c.id]!.side !== c.side && <TypingIndicator name={chat.typing[c.id]!.name} />}
      {c.canWrite && <Composer conversation={c} attachment={attachment} onAttachmentUsed={onAttachmentUsed} />}
      {!c.canWrite && c.side === null && <p className="border-t border-line px-4 py-3 text-sm text-slate">Read-only: administrators can review conversations but not write in them.</p>}
    </section>
  );
}

// ---------- header ----------

function Header({ conversation: c, backTo }: { conversation: Conversation; backTo?: string }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [menu, setMenu] = useState(false);
  const [report, setReport] = useState(false);
  const presence = usePresence(c.counterpart.userIds, c.counterpart);
  const mute = useMutation({
    mutationFn: () => chatApi.mute(c.id, !c.muted),
    onSuccess: (v) => {
      qc.setQueryData(chatKeys.conversation(c.id), v);
      void qc.invalidateQueries({ queryKey: chatKeys.unread });
      toast.ok(v.muted ? 'Conversation muted' : 'Notifications on');
    },
  });
  return (
    <header className="flex items-center gap-3 border-b border-line px-4 py-3">
      {backTo && <Link to={backTo} className="btn -ml-2 px-2 md:hidden" aria-label="Back to conversations"><ArrowLeft className="h-5 w-5" /></Link>}
      <ChatAvatar name={c.counterpart.name} online={presence.online} shop={c.counterpart.kind === 'SHOP'} />
      <div className="min-w-0 flex-1">
        <h2 className="truncate font-display text-lg font-bold leading-tight">
          {c.counterpart.kind === 'SHOP' && c.counterpart.vendorId
            ? <Link to={`/vendors/${c.counterpart.vendorId}`} className="hover:underline">{c.counterpart.name}</Link>
            : c.counterpart.name}
        </h2>
        <PresenceLine counterpart={c.counterpart} />
      </div>
      {c.side && (
        <div className="relative">
          <button className="btn px-2" aria-label="Conversation options" aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu(!menu)}>
            <MoreHorizontal className="h-5 w-5" />
          </button>
          {menu && (
            <div role="menu" className="absolute right-0 z-20 mt-1 w-56 rounded-lg border border-line bg-surface p-1 shadow-lg" onMouseLeave={() => setMenu(false)}>
              <button role="menuitem" className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm hover:bg-mist" onClick={() => { setMenu(false); mute.mutate(); }}>
                {c.muted ? <Bell className="h-4 w-4" /> : <BellOff className="h-4 w-4" />}{c.muted ? 'Turn notifications on' : 'Mute notifications'}
              </button>
              <button role="menuitem" className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-brick hover:bg-mist" onClick={() => { setMenu(false); setReport(true); }}>
                <Flag className="h-4 w-4" />Report conversation
              </button>
            </div>
          )}
        </div>
      )}
      <ReportDialog conversationId={c.id} open={report} onClose={() => setReport(false)} />
    </header>
  );
}

const REASONS: [string, string][] = [
  ['SCAM', 'Scam or fraud'], ['OFF_PLATFORM_PAYMENT', 'Asked to pay outside MarketFlow'], ['ABUSE', 'Harassment or abuse'],
  ['SPAM', 'Spam'], ['OTHER', 'Something else'],
];

function ReportDialog({ conversationId, open, onClose }: { conversationId: string; open: boolean; onClose: () => void }) {
  const toast = useToast();
  const [reason, setReason] = useState('SCAM');
  const [details, setDetails] = useState('');
  const send = useMutation({
    mutationFn: () => chatApi.report(conversationId, reason, details),
    onSuccess: () => { toast.ok('Thanks. Our team will review this conversation.'); onClose(); setDetails(''); },
    onError: (e) => toast.error(e instanceof ApiError ? e.message : 'The report could not be sent'),
  });
  return (
    <Dialog open={open} onClose={onClose} title="Report this conversation">
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); send.mutate(); }}>
        <Field label="What is wrong?" htmlFor="report-reason">
          <Select id="report-reason" value={reason} onChange={(e) => setReason(e.target.value)}>
            {REASONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </Select>
        </Field>
        <Field label="Details (optional)" htmlFor="report-details" hint="Our support team sees the whole conversation.">
          <Textarea id="report-details" rows={3} maxLength={1000} value={details} onChange={(e) => setDetails(e.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn-danger" disabled={send.isPending}>{send.isPending && <Spinner />}Send report</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------- timeline ----------

type MessagesQuery = ReturnType<typeof useMessages>;

function Timeline({ conversation: c, messages, orderLink }: { conversation: Conversation; messages: MessagesQuery; orderLink?: Props['orderLink'] }) {
  const chat = useChat();
  const scroller = useRef<HTMLDivElement>(null);
  const top = useRef<HTMLDivElement>(null);
  const restore = useRef<number | null>(null);
  const stickToBottom = useRef(true);
  const items = useMemo(() => flatten(messages.data), [messages.data]);
  const pending = chat.pending[c.id] ?? [];
  const mySide = c.side ?? 'VENDOR'; // administrators see the buyer on the left, the shop on the right

  // load older messages when the top sentinel scrolls into view; keep the reading position when they arrive
  useEffect(() => {
    const el = top.current;
    if (!el || !messages.hasNextPage) return undefined;
    const io = new IntersectionObserver((entries) => {
      if (entries[0]?.isIntersecting && !messages.isFetchingNextPage && scroller.current) {
        restore.current = scroller.current.scrollHeight - scroller.current.scrollTop;
        void messages.fetchNextPage();
      }
    }, { root: scroller.current, rootMargin: '200px 0px 0px 0px' });
    io.observe(el);
    return () => io.disconnect();
  }, [messages, messages.hasNextPage, messages.isFetchingNextPage]);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    if (restore.current !== null) {
      el.scrollTop = el.scrollHeight - restore.current;
      restore.current = null;
    } else if (stickToBottom.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [items.length, pending.length]);

  // mark as read what the viewer can see (tab visible), using the newest message from the other side
  const lastFromThem = useMemo(() => items.reduce((max, m) => (m.senderRole !== mySide ? Math.max(max, m.id) : max), 0), [items, mySide]);
  useEffect(() => {
    if (!c.side || lastFromThem <= c.myLastReadMessageId) return undefined;
    const mark = () => { if (document.visibilityState === 'visible') chat.markRead(c.id, lastFromThem); };
    const t = window.setTimeout(mark, 400);
    document.addEventListener('visibilitychange', mark);
    return () => { window.clearTimeout(t); document.removeEventListener('visibilitychange', mark); };
  }, [c.id, c.side, c.myLastReadMessageId, lastFromThem, chat]);

  const onScroll = () => {
    const el = scroller.current;
    if (el) stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  };

  let lastDay = '';
  return (
    <div ref={scroller} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto px-3 py-3 sm:px-4">
      <div ref={top} />
      {messages.isFetchingNextPage && <div className="flex justify-center py-2"><Spinner /></div>}
      {!messages.hasNextPage && items.length > 0 && <p className="py-2 text-center text-xs text-slate">This is the start of your conversation.</p>}
      {items.length === 0 && pending.length === 0 && (
        <div className="flex h-full flex-col items-center justify-center gap-2 text-center text-slate">
          <MessageSquareText className="h-8 w-8" aria-hidden />
          <p className="max-w-xs text-sm">
            {c.side === 'BUYER' ? `Ask ${c.counterpart.name} about sizes, stock, shipping or your order.` : 'No messages yet.'}
          </p>
        </div>
      )}
      <ol className="flex flex-col gap-1" role="log" aria-live="polite" aria-relevant="additions" aria-label="Messages">
        {items.map((m) => {
          const day = new Date(m.createdAt).toDateString();
          const separator = day !== lastDay ? <DaySeparator iso={m.createdAt} /> : null;
          lastDay = day;
          return (
            <li key={m.id}>
              {separator}
              <MessageBubble message={m} mine={m.senderRole === mySide} canChange={!!c.side && c.canWrite}
                seen={m.id <= c.theirLastReadMessageId} orderLink={orderLink} />
            </li>
          );
        })}
        {pending.map((p) => <li key={p.clientMessageId}><PendingBubble pending={p} /></li>)}
      </ol>
    </div>
  );
}

function MessageBubble({ message: m, mine, canChange, seen, orderLink }: {
  message: ChatMessage; mine: boolean; canChange: boolean; seen: boolean; orderLink?: Props['orderLink'];
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(m.body ?? '');
  const [actions, setActions] = useState(false);
  const changeable = mine && canChange && !m.deleted && Date.now() - new Date(m.createdAt).getTime() < EDIT_WINDOW_MS;
  const edit = useMutation({
    mutationFn: () => chatApi.edit(m.id, text),
    onSuccess: (v) => { upsertMessages(qc, m.conversationId, [v]); setEditing(false); },
    onError: (e) => toast.error(e instanceof ApiError ? e.message : 'The message could not be changed'),
  });
  const remove = useMutation({
    mutationFn: () => chatApi.remove(m.id),
    onSuccess: (v) => upsertMessages(qc, m.conversationId, [v]),
    onError: (e) => toast.error(e instanceof ApiError ? e.message : 'The message could not be deleted'),
  });

  if (m.type === 'SYSTEM') {
    return (
      <div className="mx-auto my-2 max-w-md rounded-lg bg-mist px-3 py-2 text-center text-xs text-slate">
        <span className="font-semibold text-ink">MarketFlow</span> · {m.body}
        <span className="ml-1 whitespace-nowrap">{chatTime(m.createdAt)}</span>
      </div>
    );
  }
  return (
    <div className={clsx('group flex', mine ? 'justify-end' : 'justify-start')}>
      <div className={clsx('flex max-w-[85%] flex-col gap-1 sm:max-w-[70%]', mine ? 'items-end' : 'items-start')}>
        {m.deleted ? (
          <p className="rounded-2xl border border-dashed border-line px-3 py-2 text-sm italic text-slate">Message deleted</p>
        ) : (
          <>
            {(m.type === 'PRODUCT_CARD' || m.type === 'ORDER_CARD') && <CardBubble message={m} orderLink={orderLink} />}
            {m.type === 'IMAGE' && m.attachmentUrl && <ChatImage url={m.attachmentUrl} alt={m.body ? `Photo: ${m.body}` : 'Photo'} />}
            {editing ? (
              <form className="flex w-72 max-w-full flex-col gap-2" onSubmit={(e) => { e.preventDefault(); edit.mutate(); }}>
                <label className="sr-only" htmlFor={`edit-${m.id}`}>Edit message</label>
                <Textarea id={`edit-${m.id}`} rows={2} maxLength={MAX_LENGTH} value={text} onChange={(e) => setText(e.target.value)} autoFocus />
                <div className="flex justify-end gap-2">
                  <button type="button" className="btn-quiet px-3 py-1.5" onClick={() => { setEditing(false); setText(m.body ?? ''); }}>Cancel</button>
                  <button className="btn-primary px-3 py-1.5" disabled={edit.isPending || !text.trim()}>Save</button>
                </div>
              </form>
            ) : m.body ? (
              <p className={clsx('whitespace-pre-wrap break-words rounded-2xl px-3 py-2 text-sm leading-relaxed',
                mine ? 'rounded-br-md bg-ink text-mist' : 'rounded-bl-md border border-line bg-surface')}>{m.body}</p>
            ) : null}
          </>
        )}
        <div className="flex items-center gap-1.5 text-[11px] text-slate">
          <time dateTime={m.createdAt}>{chatTime(m.createdAt)}</time>
          {m.editedAt && !m.deleted && <span>· edited</span>}
          {mine && !m.deleted && <Ticks seen={seen} />}
          {changeable && !editing && (
            <span className="relative">
              <button className="rounded p-0.5 opacity-60 hover:opacity-100 focus:opacity-100" aria-label="Message options" aria-expanded={actions}
                onClick={() => setActions(!actions)}><MoreHorizontal className="h-3.5 w-3.5" /></button>
              {actions && (
                <span role="menu" className="absolute bottom-5 right-0 z-10 flex w-32 flex-col rounded-lg border border-line bg-surface p-1 text-xs text-ink shadow-lg" onMouseLeave={() => setActions(false)}>
                  {m.type === 'TEXT' && <button role="menuitem" className="flex items-center gap-2 rounded px-2 py-1.5 hover:bg-mist" onClick={() => { setActions(false); setEditing(true); }}><Pencil className="h-3.5 w-3.5" />Edit</button>}
                  <button role="menuitem" className="flex items-center gap-2 rounded px-2 py-1.5 text-brick hover:bg-mist" onClick={() => { setActions(false); remove.mutate(); }}><Trash2 className="h-3.5 w-3.5" />Delete</button>
                </span>
              )}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

function PendingBubble({ pending: p }: { pending: PendingMessage }) {
  const chat = useChat();
  const failed = p.status === 'failed';
  return (
    <div className="flex justify-end">
      <div className="flex max-w-[85%] flex-col items-end gap-1 sm:max-w-[70%]">
        {p.localImageUrl && <img src={p.localImageUrl} alt="Photo being sent" className={clsx('max-h-56 rounded-lg', !failed && 'opacity-70')} />}
        {(p.type === 'PRODUCT_CARD' || p.type === 'ORDER_CARD') && (
          <span className="rounded-lg border border-line bg-surface px-3 py-2 text-xs text-slate">{p.type === 'PRODUCT_CARD' ? 'Product card' : 'Order card'}</span>
        )}
        {p.body && (
          <p className={clsx('whitespace-pre-wrap break-words rounded-2xl rounded-br-md px-3 py-2 text-sm', failed ? 'border border-brick/40 bg-brick/10 text-ink' : 'bg-ink/80 text-mist')}>{p.body}</p>
        )}
        <div className="flex items-center gap-2 text-[11px] text-slate">
          <PendingTicks failed={failed} />
          {failed && p.retryable !== false && <button className="font-semibold underline" onClick={() => chat.retry(p)}>Retry</button>}
          {failed && <button className="underline" onClick={() => chat.discard(p)}>Discard</button>}
        </div>
        {failed && p.error && <p role="alert" className="max-w-xs text-right text-xs text-brick">{p.error}</p>}
      </div>
    </div>
  );
}

// ---------- composer ----------

function Composer({ conversation: c, attachment, onAttachmentUsed }: { conversation: Conversation; attachment?: Attachment | null; onAttachmentUsed?: () => void }) {
  const chat = useChat();
  const toast = useToast();
  const [text, setText] = useState('');
  const [card, setCard] = useState<Attachment | null>(attachment ?? null);
  const [uploading, setUploading] = useState(false);
  const [quick, setQuick] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const typingSince = useRef(0);
  const stopTimer = useRef<number | null>(null);
  const replies = useQuickReplies(c.side === 'VENDOR');

  useEffect(() => { setCard(attachment ?? null); }, [attachment]);
  // grow with the text up to ~6 lines
  useLayoutEffect(() => {
    const el = area.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [text]);

  const stopTyping = useCallback(() => {
    if (stopTimer.current) window.clearTimeout(stopTimer.current);
    stopTimer.current = null;
    if (typingSince.current) {
      typingSince.current = 0;
      chat.sendTyping(c.id, false);
    }
  }, [chat, c.id]);

  useEffect(() => () => stopTyping(), [stopTyping]);

  const onChange = (value: string) => {
    setText(value);
    const now = Date.now();
    if (value && now - typingSince.current > 2000) {
      typingSince.current = now;
      chat.sendTyping(c.id, true);
    }
    if (stopTimer.current) window.clearTimeout(stopTimer.current);
    stopTimer.current = window.setTimeout(stopTyping, 3000);
  };

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    const body = text.trim();
    if (!body && !card) return;
    if (card) {
      // the question travels with the card as one message, so it previews and reads as one thought
      chat.send(c.id, { type: card.type, productId: card.productId, suborderId: card.suborderId, body: body || undefined });
      setCard(null);
      onAttachmentUsed?.();
    } else {
      chat.send(c.id, { type: 'TEXT', body });
    }
    setText('');
    stopTyping();
    area.current?.focus({ preventScroll: true });
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      submit();
    }
  };

  const pickImage = async (file: File | undefined) => {
    if (!file) return;
    if (!IMAGE_TYPES.includes(file.type)) { toast.error('Send a PNG, JPEG or WebP image'); return; }
    if (file.size > MAX_IMAGE_BYTES) { toast.error('Images can be at most 5 MB'); return; }
    setUploading(true);
    try {
      const uploaded = await chatApi.upload(c.id, file);
      chat.send(c.id, { type: 'IMAGE', attachmentId: uploaded.id, body: text.trim() || undefined, localImageUrl: URL.createObjectURL(file) });
      setText('');
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'The image could not be uploaded');
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const remaining = MAX_LENGTH - [...text].length;
  return (
    <form onSubmit={submit} className="border-t border-line bg-surface px-3 py-2 sm:px-4">
      {card && (
        <div className="mb-2 flex items-center gap-2 rounded-lg bg-mist px-3 py-1.5 text-sm">
          <span className="min-w-0 flex-1 truncate">Attaching: <span className="font-medium">{card.label}</span></span>
          <button type="button" className="rounded p-1 hover:bg-line" aria-label="Remove attachment" onClick={() => { setCard(null); onAttachmentUsed?.(); }}><X className="h-4 w-4" /></button>
        </div>
      )}
      {quick && replies.data && (
        <div className="mb-2 flex flex-wrap gap-1.5" role="group" aria-label="Quick replies">
          {replies.data.length === 0 && <span className="text-sm text-slate">No quick replies yet. Add them under Quick replies on this page.</span>}
          {replies.data.map((q) => (
            <button key={q.id} type="button" className="rounded-full border border-line px-3 py-1 text-sm hover:border-ink/40"
              onClick={() => { setText((t) => (t ? `${t}\n${q.body}` : q.body)); setQuick(false); area.current?.focus({ preventScroll: true }); }}>{q.title}</button>
          ))}
        </div>
      )}
      <div className="flex items-end gap-2">
        <input ref={fileInput} type="file" accept={IMAGE_TYPES.join(',')} className="hidden" onChange={(e) => void pickImage(e.target.files?.[0])} aria-hidden tabIndex={-1} />
        <button type="button" className="btn px-2 text-slate hover:text-ink" aria-label="Attach an image" disabled={uploading} onClick={() => fileInput.current?.click()}>
          {uploading ? <Spinner className="h-5 w-5" /> : <ImagePlus className="h-5 w-5" />}
        </button>
        {c.side === 'VENDOR' && (
          <button type="button" className="btn px-2 text-slate hover:text-ink" aria-label="Quick replies" aria-expanded={quick} onClick={() => setQuick(!quick)}>
            <MessageSquareText className="h-5 w-5" />
          </button>
        )}
        <label htmlFor={`composer-${c.id}`} className="sr-only">Message {c.counterpart.name}</label>
        <textarea id={`composer-${c.id}`} ref={area} rows={1} value={text} maxLength={MAX_LENGTH}
          onChange={(e) => onChange(e.target.value)} onKeyDown={onKeyDown} onBlur={stopTyping}
          placeholder={`Message ${c.counterpart.name}`} className="input max-h-40 min-h-[2.75rem] resize-none py-2.5" />
        <button className="btn-primary h-11 px-3" aria-label="Send" disabled={!text.trim() && !card}><Send className="h-4 w-4" /></button>
      </div>
      <div className="mt-1 flex justify-between text-[11px] text-slate">
        <span>Keep payments and contact details on MarketFlow: your purchase protection depends on it.</span>
        {remaining < 200 && <span className={clsx(remaining < 0 && 'text-brick')} aria-live="polite">{remaining} left</span>}
      </div>
    </form>
  );
}
