/**
 * One chat connection per signed-in tab. Routes pushed events into the query caches, keeps typing indicators
 * and presence, and owns optimistic sending:
 *
 *   1. the message is shown at once as "sending" (pending, keyed by its clientMessageId);
 *   2. it is published over the socket; the server's MESSAGE_CREATED with the same clientMessageId replaces it;
 *   3. if no acknowledgement arrives within a few seconds (or the socket is down) it is sent over HTTP with the
 *      same clientMessageId — the server is idempotent on it, so the two paths can never create two messages;
 *   4. a refusal (contact details, rate limit, closed conversation) marks it failed with the reason; network
 *      errors can be retried.
 *
 * After a lost connection is re-established, every open conversation is resynchronised with ?after=<newest id>
 * (plus messages edited or deleted meanwhile), and inbox and badges are refetched.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError, onSessionChange } from '@/lib/api';
import type { ChatEvent, ChatMessage, ChatMessageType, ChatSide, Conversation, MessagePage } from '@/lib/types';
import { chatApi, chatKeys, newClientId, newestId, patchConversation, refreshInboxSoon, upsertMessages } from './cache';
import { createChatTransport, type ChatTransport, type ConnectionState } from './transport';

export interface Draft {
  type: ChatMessageType;
  body?: string;
  attachmentId?: string;
  /** shown while an image is uploading/sending */
  localImageUrl?: string;
  productId?: string;
  suborderId?: string;
}

export interface PendingMessage extends Draft {
  clientMessageId: string;
  conversationId: string;
  createdAt: string;
  status: 'sending' | 'failed';
  error?: string;
  /** false for refusals that will fail again unchanged (e.g. contact details) */
  retryable?: boolean;
}

interface TypingState { name: string; side: ChatSide; until: number }

interface ChatState {
  connection: ConnectionState;
  pending: Record<string, PendingMessage[]>;
  typing: Record<string, TypingState | undefined>;
  presence: Record<string, { online: boolean; lastSeenAt?: string | null } | undefined>;
  send: (conversationId: string, draft: Draft) => void;
  retry: (p: PendingMessage) => void;
  discard: (p: PendingMessage) => void;
  sendTyping: (conversationId: string, typing: boolean) => void;
  markRead: (conversationId: string, lastReadMessageId: number) => void;
}

const ChatContext = createContext<ChatState | null>(null);

const ACK_TIMEOUT_MS = 5000;
const TYPING_TTL_MS = 6000;

export function ChatProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const qc = useQueryClient();
  const transport = useRef<ChatTransport | null>(null);
  const [connection, setConnection] = useState<ConnectionState>('idle');
  const [pending, setPending] = useState<Record<string, PendingMessage[]>>({});
  const [typing, setTyping] = useState<ChatState['typing']>({});
  const [presence, setPresence] = useState<ChatState['presence']>({});
  const ackTimers = useRef(new Map<string, number>());
  const lostAt = useRef<number | null>(null);
  const pendingRef = useRef(pending);
  pendingRef.current = pending;

  const updatePending = useCallback((conversationId: string, fn: (list: PendingMessage[]) => PendingMessage[]) => {
    setPending((all) => {
      const next = fn(all[conversationId] ?? []);
      const copy = { ...all };
      if (next.length) copy[conversationId] = next; else delete copy[conversationId];
      return copy;
    });
  }, []);

  const acknowledge = useCallback((m: ChatMessage) => {
    if (!m.clientMessageId) return;
    const timer = ackTimers.current.get(m.clientMessageId);
    if (timer) window.clearTimeout(timer);
    ackTimers.current.delete(m.clientMessageId);
    updatePending(m.conversationId, (list) => list.filter((p) => p.clientMessageId !== m.clientMessageId));
  }, [updatePending]);

  const received = useCallback((m: ChatMessage) => {
    upsertMessages(qc, m.conversationId, [m]);
    acknowledge(m);
    patchConversation(qc, m.conversationId, (c) => ({ ...c, lastMessageAt: m.createdAt }));
    refreshInboxSoon(qc);
  }, [qc, acknowledge]);

  const fail = useCallback((conversationId: string, clientMessageId: string, error: string, retryable: boolean) => {
    const timer = ackTimers.current.get(clientMessageId);
    if (timer) window.clearTimeout(timer);
    ackTimers.current.delete(clientMessageId);
    updatePending(conversationId, (list) => list.map((p) => p.clientMessageId === clientMessageId
      ? { ...p, status: 'failed', error, retryable } : p));
  }, [updatePending]);

  const sendOverHttp = useCallback(async (p: PendingMessage) => {
    try {
      const m = await chatApi.send(p.conversationId, {
        clientMessageId: p.clientMessageId, type: p.type, body: p.body, attachmentId: p.attachmentId,
        productId: p.productId, suborderId: p.suborderId,
      });
      received(m);
    } catch (e) {
      if (e instanceof ApiError && e.status < 500) {
        fail(p.conversationId, p.clientMessageId, e.message, e.status === 429 || e.status === 408);
      } else {
        fail(p.conversationId, p.clientMessageId, 'Not sent: check your connection and try again.', true);
      }
    }
  }, [received, fail]);

  const dispatch = useCallback((p: PendingMessage) => {
    const sent = transport.current?.publish('/app/chat.send', {
      conversationId: p.conversationId, clientMessageId: p.clientMessageId, type: p.type, body: p.body,
      attachmentId: p.attachmentId, productId: p.productId, suborderId: p.suborderId,
    });
    if (!sent) {
      void sendOverHttp(p);
      return;
    }
    ackTimers.current.set(p.clientMessageId, window.setTimeout(() => {
      ackTimers.current.delete(p.clientMessageId);
      const still = pendingRef.current[p.conversationId]?.find((x) => x.clientMessageId === p.clientMessageId);
      if (still && still.status === 'sending') void sendOverHttp(p);
    }, ACK_TIMEOUT_MS));
  }, [sendOverHttp]);

  const resync = useCallback(async () => {
    const since = lostAt.current ? new Date(lostAt.current - 60_000).toISOString() : undefined;
    lostAt.current = null;
    const open = qc.getQueriesData<InfiniteData<MessagePage, unknown>>({ queryKey: ['chat', 'messages'] });
    await Promise.all(open.map(async ([key, data]) => {
      const id = String(key[2]);
      const after = newestId(data);
      if (!data || after === 0) {
        void qc.invalidateQueries({ queryKey: chatKeys.messages(id) });
        return;
      }
      try {
        const page = await chatApi.after(id, after, since);
        upsertMessages(qc, id, [...(page.changed ?? []), ...page.items]);
        page.items.forEach(acknowledge);
        if (page.hasMore) void qc.invalidateQueries({ queryKey: chatKeys.messages(id) });
      } catch { /* next reconnect or a refetch will fix it */ }
      void qc.invalidateQueries({ queryKey: chatKeys.conversation(id) });
    }));
    refreshInboxSoon(qc);
    // anything still waiting for an acknowledgement goes again (idempotent on clientMessageId)
    Object.values(pendingRef.current).flat().filter((p) => p.status === 'sending').forEach(dispatch);
  }, [qc, acknowledge, dispatch]);

  const onEvent = useCallback((e: ChatEvent) => {
    switch (e.queue) {
      case 'messages': {
        const p = e.payload;
        if (p.message) received(p.message);
        if (p.kind === 'CONVERSATION_UPDATED') {
          void qc.invalidateQueries({ queryKey: chatKeys.conversation(p.conversationId) });
          refreshInboxSoon(qc);
        }
        if (p.message && p.kind === 'MESSAGE_CREATED') {
          setTyping((t) => (t[p.conversationId] ? { ...t, [p.conversationId]: undefined } : t));
        }
        break;
      }
      case 'typing': {
        const p = e.payload;
        setTyping((t) => ({ ...t, [p.conversationId]: p.typing ? { name: p.name, side: p.side, until: Date.now() + TYPING_TTL_MS } : undefined }));
        break;
      }
      case 'receipts': {
        const p = e.payload;
        const conv = qc.getQueryData<Conversation>(chatKeys.conversation(p.conversationId));
        if (conv && conv.side && conv.side !== p.side) {
          patchConversation(qc, p.conversationId, (c) => ({ ...c, theirLastReadMessageId: Math.max(c.theirLastReadMessageId, p.lastReadMessageId) }));
        } else if (conv && conv.side === p.side) {
          patchConversation(qc, p.conversationId, (c) => ({ ...c, myLastReadMessageId: Math.max(c.myLastReadMessageId, p.lastReadMessageId) }));
        }
        refreshInboxSoon(qc);
        break;
      }
      case 'presence': {
        const p = e.payload;
        setPresence((all) => ({ ...all, [p.userId]: { online: p.online, lastSeenAt: p.lastSeenAt } }));
        break;
      }
      case 'errors': {
        const p = e.payload;
        if (p.clientMessageId && p.conversationId) {
          fail(p.conversationId, p.clientMessageId, p.message, p.code === 'too_many_requests');
        }
        break;
      }
    }
  }, [qc, received, fail]);

  // connection lifecycle follows the signed-in user; a renewed token reconnects with the new one
  useEffect(() => {
    if (!user) return undefined;
    const t = createChatTransport();
    transport.current = t;
    t.start({
      onState: (s) => {
        if (s === 'reconnecting' && lostAt.current === null) lostAt.current = Date.now();
        setConnection(s);
      },
      onConnected: (resumed) => {
        if (resumed) void resync();
      },
      onEvent,
    });
    let lastToken: string | null = null;
    const off = onSessionChange((s) => {
      if (s && lastToken && s.accessToken !== lastToken) t.restart();
      lastToken = s?.accessToken ?? null;
    });
    const timers = ackTimers.current;
    return () => {
      off();
      t.stop();
      transport.current = null;
      timers.forEach((id) => window.clearTimeout(id));
      timers.clear();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reconnect only when the person changes
  }, [user?.id]);

  // expire stale typing indicators (a "stopped typing" frame can be lost)
  useEffect(() => {
    const id = window.setInterval(() => {
      const now = Date.now();
      setTyping((t) => {
        const expired = Object.entries(t).filter(([, v]) => v && v.until < now);
        if (expired.length === 0) return t;
        const copy = { ...t };
        expired.forEach(([k]) => { copy[k] = undefined; });
        return copy;
      });
    }, 1000);
    return () => window.clearInterval(id);
  }, []);

  const send = useCallback((conversationId: string, draft: Draft) => {
    const p: PendingMessage = { ...draft, conversationId, clientMessageId: newClientId(), createdAt: new Date().toISOString(), status: 'sending' };
    updatePending(conversationId, (list) => [...list, p]);
    dispatch(p);
  }, [updatePending, dispatch]);

  const retry = useCallback((p: PendingMessage) => {
    const again: PendingMessage = { ...p, status: 'sending', error: undefined };
    updatePending(p.conversationId, (list) => list.map((x) => (x.clientMessageId === p.clientMessageId ? again : x)));
    dispatch(again);
  }, [updatePending, dispatch]);

  const discard = useCallback((p: PendingMessage) => {
    updatePending(p.conversationId, (list) => list.filter((x) => x.clientMessageId !== p.clientMessageId));
  }, [updatePending]);

  const sendTyping = useCallback((conversationId: string, isTyping: boolean) => {
    transport.current?.publish('/app/chat.typing', { conversationId, typing: isTyping });
  }, []);

  const markRead = useCallback((conversationId: string, lastReadMessageId: number) => {
    patchConversation(qc, conversationId, (c) => ({ ...c, myLastReadMessageId: Math.max(c.myLastReadMessageId, lastReadMessageId) }));
    const sent = transport.current?.publish('/app/chat.read', { conversationId, lastReadMessageId });
    if (!sent) {
      void chatApi.read(conversationId, lastReadMessageId).catch(() => undefined).finally(() => refreshInboxSoon(qc));
    } else {
      refreshInboxSoon(qc);
    }
  }, [qc]);

  const value = useMemo<ChatState>(() => ({ connection, pending, typing, presence, send, retry, discard, sendTyping, markRead }),
    [connection, pending, typing, presence, send, retry, discard, sendTyping, markRead]);
  return <ChatContext.Provider value={value}>{children}</ChatContext.Provider>;
}

export function useChat(): ChatState {
  const ctx = useContext(ChatContext);
  if (!ctx) throw new Error('useChat must be used inside ChatProvider');
  return ctx;
}

/** Live presence of a counterpart: the latest pushed event wins over what the API said when it was loaded. */
export function usePresence(userIds: string[], fallback: { online: boolean; lastSeenAt?: string | null }) {
  const { presence } = useChat();
  let online = false;
  let lastSeenAt: string | null | undefined = fallback.lastSeenAt;
  let known = false;
  for (const id of userIds) {
    const p = presence[id];
    if (p) {
      known = true;
      online ||= p.online;
      if (p.lastSeenAt && (!lastSeenAt || p.lastSeenAt > lastSeenAt)) lastSeenAt = p.lastSeenAt;
    }
  }
  return known ? { online, lastSeenAt: online ? null : lastSeenAt } : fallback;
}
