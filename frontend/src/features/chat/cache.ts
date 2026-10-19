/**
 * Query keys, REST calls and cache surgery for chat. Real-time events and optimistic sends write into the same
 * TanStack Query caches the screens read, so a message appears exactly once whichever path delivered it.
 */
import type { InfiniteData, QueryClient } from '@tanstack/react-query';
import { http, qs } from '@/lib/api';
import type {
  ChatMessage, ChatSide, Conversation, ConversationSummary, CursorPage, MessagePage, QuickReply, UnreadCounts,
} from '@/lib/types';

export const chatKeys = {
  all: ['chat'] as const,
  inbox: (side: ChatSide) => ['chat', 'inbox', side] as const,
  conversation: (id: string) => ['chat', 'conversation', id] as const,
  messages: (id: string) => ['chat', 'messages', id] as const,
  unread: ['chat', 'unread'] as const,
  quickReplies: ['chat', 'quick-replies'] as const,
};

export const PAGE_SIZE = 30;

export const chatApi = {
  inbox: (side: ChatSide, cursor?: string | null) =>
    http.get<CursorPage<ConversationSummary>>(`${side === 'VENDOR' ? '/api/v1/vendor/chat' : '/api/v1/chat'}/conversations${qs({ cursor, limit: 20 })}`),
  conversation: (id: string) => http.get<Conversation>(`/api/v1/chat/conversations/${id}`),
  start: (body: { vendorId: string; productId?: string; suborderId?: string }) =>
    http.post<Conversation>('/api/v1/chat/conversations', body),
  messages: (id: string, before?: number) =>
    http.get<MessagePage>(`/api/v1/chat/conversations/${id}/messages${qs({ before, limit: PAGE_SIZE })}`),
  after: (id: string, after: number, since?: string) =>
    http.get<MessagePage>(`/api/v1/chat/conversations/${id}/messages${qs({ after, since, limit: 100 })}`),
  send: (id: string, body: Record<string, unknown>) => http.post<ChatMessage>(`/api/v1/chat/conversations/${id}/messages`, body),
  read: (id: string, lastReadMessageId: number) =>
    http.post<{ lastReadMessageId: number }>(`/api/v1/chat/conversations/${id}/read`, { lastReadMessageId }),
  edit: (messageId: number, body: string) => http.patch<ChatMessage>(`/api/v1/chat/messages/${messageId}`, { body }),
  remove: (messageId: number) => http.del<ChatMessage>(`/api/v1/chat/messages/${messageId}`),
  mute: (id: string, muted: boolean) => http.put<Conversation>(`/api/v1/chat/conversations/${id}/mute`, { muted }),
  report: (id: string, reason: string, details: string) =>
    http.post(`/api/v1/chat/conversations/${id}/report`, { reason, details }),
  unread: () => http.get<UnreadCounts>('/api/v1/chat/unread-count'),
  upload: (conversationId: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return http.post<{ id: string; url: string; contentType: string; sizeBytes: number }>(
      `/api/v1/chat/attachments${qs({ conversationId })}`, form);
  },
  quickReplies: () => http.get<QuickReply[]>('/api/v1/vendor/chat/quick-replies'),
  saveQuickReply: (q: { id?: string; title: string; body: string; sortOrder?: number }) =>
    q.id ? http.put<QuickReply>(`/api/v1/vendor/chat/quick-replies/${q.id}`, q)
      : http.post<QuickReply>('/api/v1/vendor/chat/quick-replies', q),
  deleteQuickReply: (id: string) => http.del(`/api/v1/vendor/chat/quick-replies/${id}`),
};

type Pages = InfiniteData<MessagePage, unknown>;

/** All messages of a conversation in the cache, oldest first. Pages are stored newest page first. */
export function flatten(data: Pages | undefined): ChatMessage[] {
  if (!data) return [];
  return [...data.pages].reverse().flatMap((p) => p.items);
}

export function newestId(data: Pages | undefined): number {
  return flatten(data).reduce((max, m) => Math.max(max, m.id), 0);
}

/** Adds or replaces messages (by id) keeping every page sorted; new ones go to the newest page. */
export function upsertMessages(qc: QueryClient, conversationId: string, incoming: ChatMessage[]) {
  if (incoming.length === 0) return;
  qc.setQueryData<Pages>(chatKeys.messages(conversationId), (data) => {
    if (!data || data.pages.length === 0) return data;
    const byId = new Map(incoming.map((m) => [m.id, m]));
    const pages = data.pages.map((p) => ({
      ...p,
      items: p.items.map((m) => {
        const replacement = byId.get(m.id);
        if (replacement) byId.delete(m.id);
        return replacement ?? m;
      }),
    }));
    if (byId.size > 0) {
      const first = pages[0]!;
      first.items = [...first.items, ...byId.values()].sort((a, b) => a.id - b.id);
    }
    return { ...data, pages };
  });
}

export function patchConversation(qc: QueryClient, id: string, patch: (c: Conversation) => Conversation) {
  qc.setQueryData<Conversation>(chatKeys.conversation(id), (c) => (c ? patch(c) : c));
}

/**
 * Reading a conversation clears its unread count in the inbox and the header badge at once; the refetch that
 * follows the server's read receipt confirms it. Without this the row kept "1 unread" while being read.
 */
export function clearUnread(qc: QueryClient, conversationId: string, side: ChatSide) {
  let cleared = 0;
  qc.setQueryData<InfiniteData<CursorPage<ConversationSummary>, unknown>>(chatKeys.inbox(side), (d) => d && ({
    ...d,
    pages: d.pages.map((p) => ({
      ...p,
      items: p.items.map((i) => {
        if (i.id !== conversationId || i.unread === 0) return i;
        cleared += i.unread;
        return { ...i, unread: 0 };
      }),
    })),
  }));
  if (cleared > 0) {
    qc.setQueryData<UnreadCounts>(chatKeys.unread, (u) => u && (side === 'VENDOR'
      ? { ...u, asVendor: Math.max(0, u.asVendor - cleared) }
      : { ...u, asBuyer: Math.max(0, u.asBuyer - cleared) }));
  }
}

/** Inbox and badges are cheap to refetch; coalesce bursts of events into one refetch. */
let inboxTimer: number | null = null;
export function refreshInboxSoon(qc: QueryClient) {
  if (inboxTimer) return;
  inboxTimer = window.setTimeout(() => {
    inboxTimer = null;
    void qc.invalidateQueries({ queryKey: ['chat', 'inbox'] });
    void qc.invalidateQueries({ queryKey: chatKeys.unread });
  }, 300);
}

export function newClientId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16));
}
