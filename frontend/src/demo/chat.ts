/**
 * Chat for the public demo: the same API and the same rules as chat-service, inside the browser.
 *
 * Data lives in the demo database (localStorage) like everything else. "Real time" between tabs uses a
 * BroadcastChannel: every delivery the real service would push through Redis to a user's STOMP session is posted
 * on the channel, and each tab hands it to its chat connection when it is addressed to the person signed in there.
 * Open the store in two tabs, sign in as Customer in one and as Seller in the other, and messages, typing and
 * read receipts travel live between them.
 */
import type { ChatEvent, ChatMessage, ChatMessageType, ChatSide, Conversation, ConversationSummary, QuickReply } from '@/lib/types';
import type { ChatTransport, TransportHandlers } from '@/features/chat/transport';
import { getAccessToken } from '@/lib/api';
import { MAX_LENGTH, normaliseText, REFUSAL, violations } from './chatPolicy';
import { newId, type DemoDB, type DemoOrder, type DemoUser } from './db';

// ---------- data model (part of DemoDB) ----------

export interface DemoConversation {
  id: string;
  buyerId: string;
  buyerName: string;
  vendorId: string;
  productId: string | null;
  suborderId: string | null;
  status: 'OPEN' | 'BLOCKED';
  lastMessageAt: string;
  lastMessageId: number | null;
  createdAt: string;
}

export interface DemoChatMessage {
  id: number;
  conversationId: string;
  senderId: string | null;
  senderRole: ChatSide | 'SYSTEM';
  type: ChatMessageType;
  body: string | null;
  attachmentUrl: string | null;
  metadata: Record<string, unknown> | null;
  clientMessageId: string | null;
  createdAt: string;
  editedAt: string | null;
  deletedAt: string | null;
}

export interface DemoChat {
  conversations: DemoConversation[];
  messages: DemoChatMessage[];
  participants: { conversationId: string; userId: string; side: ChatSide; lastRead: number; muted: boolean }[];
  attachments: { id: string; conversationId: string; uploaderId: string; contentType: string; dataUrl: string }[];
  reports: {
    id: string; conversationId: string; reporterId: string; reporterSide: ChatSide; reason: string; details: string | null;
    status: 'OPEN' | 'RESOLVED'; resolution: string | null; resolutionNote: string | null; resolvedAt: string | null; createdAt: string;
  }[];
  violations: { conversationId: string; userId: string; kinds: string; createdAt: string }[];
  quickReplies: (QuickReply & { vendorId: string })[];
  lastSeen: Record<string, string>;
  seq: number;
}

export const emptyChat = (): DemoChat => ({
  conversations: [], messages: [], participants: [], attachments: [], reports: [], violations: [], quickReplies: [], lastSeen: {}, seq: 1,
});

// ---------- server context (provided by server.ts) ----------

export class ChatHttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message); }
}
const notFound = (what: string) => new ChatHttpError(404, 'not_found', `${what} not found`);
const forbidden = (msg = 'You do not have permission to do that') => new ChatHttpError(403, 'forbidden', msg);
const conflict = (code: string, msg: string) => new ChatHttpError(409, code, msg);
const bad = (code: string, msg: string) => new ChatHttpError(400, code, msg);

export interface ChatServer {
  /** the current database, freshly synchronised with other tabs */
  db(): DemoDB;
  save(): void;
}

let server: ChatServer | null = null;
const iso = (ms: number) => new Date(ms).toISOString();
const chat = () => server!.db().chat;
const EDIT_WINDOW_MS = 10 * 60 * 1000;

// ---------- real-time plumbing ----------

type Wire =
  | { type: 'deliver'; recipients: string[]; event: ChatEvent }
  | { type: 'presence'; userId: string; online: boolean; at: string };

const CHANNEL = 'marketflow.demo.chat';
let channel: BroadcastChannel | null = null;
const localHandlers = new Set<{ user: () => string | null; handlers: TransportHandlers }>();
const onlineUntil = new Map<string, number>(); // other tabs' users, from presence pings
const PRESENCE_TTL = 25_000;

function tabUserId(): string | null {
  const t = getAccessToken();
  return t?.startsWith('demo.') ? t.slice('demo.'.length) : null;
}

function receive(w: Wire) {
  if (w.type === 'presence') {
    const wasOnline = isOnline(w.userId);
    if (w.online) onlineUntil.set(w.userId, Date.now() + PRESENCE_TTL); else onlineUntil.delete(w.userId);
    if (wasOnline !== isOnline(w.userId)) {
      localDeliver(everyoneLocal(), { queue: 'presence', payload: { userId: w.userId, online: w.online, lastSeenAt: w.online ? null : w.at } });
    }
    return;
  }
  localDeliver(w.recipients, w.event);
}

function everyoneLocal(): string[] {
  return [...localHandlers].map((h) => h.user()).filter((u): u is string => !!u);
}

function localDeliver(recipients: string[], event: ChatEvent) {
  for (const h of localHandlers) {
    const u = h.user();
    if (u && recipients.includes(u)) h.handlers.onEvent(event);
  }
}

/** The demo's "Redis fan-out": this tab directly, every other tab through the BroadcastChannel. */
function deliver(recipients: string[], event: ChatEvent) {
  if (recipients.length === 0) return;
  localDeliver(recipients, event);
  channel?.postMessage({ type: 'deliver', recipients, event } satisfies Wire);
}

function isOnline(userId: string): boolean {
  if (everyoneLocal().includes(userId)) return true;
  const until = onlineUntil.get(userId);
  return !!until && until > Date.now();
}

function announce(userId: string, online: boolean) {
  const at = iso(Date.now());
  if (!online) {
    chat().lastSeen[userId] = at;
    server?.save();
  }
  channel?.postMessage({ type: 'presence', userId, online, at } satisfies Wire);
}

/** Installs the chat API and the BroadcastChannel transport. Called once by installDemoBackend. */
export function installChat(s: ChatServer) {
  server = s;
  if (typeof BroadcastChannel !== 'undefined') {
    channel = new BroadcastChannel(CHANNEL);
    channel.onmessage = (e: MessageEvent<Wire>) => receive(e.data);
  }
  // keep this tab's person marked online in the other tabs
  window.setInterval(() => {
    const u = tabUserId();
    if (u) announce(u, true);
  }, 10_000);
  window.addEventListener('pagehide', () => {
    const u = tabUserId();
    if (u) announce(u, false);
  });
}

/** Chat connection for the demo build: frames are handled in the browser, events arrive over the channel. */
export function demoTransport(): ChatTransport {
  let entry: { user: () => string | null; handlers: TransportHandlers } | null = null;
  let me: string | null = null;
  let queue: Promise<void> = Promise.resolve();
  return {
    start(handlers) {
      entry = { user: tabUserId, handlers };
      localHandlers.add(entry);
      me = tabUserId();
      if (me) announce(me, true);
      handlers.onState('connected');
      handlers.onConnected(false);
    },
    stop() {
      if (entry) localHandlers.delete(entry);
      entry = null;
      if (me && !everyoneLocal().includes(me)) announce(me, false);
      me = null;
    },
    restart() { /* tokens never expire in the demo */ },
    publish(destination, body) {
      const user = currentUser();
      if (!user) return false;
      const frame = body as Record<string, unknown>;
      // A little latency, like the network, but frames are handled strictly in the order they were sent, as the
      // real server does for one STOMP session (send, send, read must not overtake each other).
      queue = queue.then(() => new Promise<void>((done) => window.setTimeout(() => {
        try { handleFrame(); } finally { done(); }
      }, 60 + Math.random() * 90)));
      const handleFrame = () => {
        try {
          if (destination === '/app/chat.send') {
            sendMessage(user, String(frame.conversationId), frame);
          } else if (destination === '/app/chat.typing') {
            typing(user, String(frame.conversationId), frame.typing === true);
          } else {
            markRead(user, String(frame.conversationId), Number(frame.lastReadMessageId));
          }
          server!.save();
        } catch (e) {
          if (e instanceof ChatHttpError && destination === '/app/chat.send') {
            entry?.handlers.onEvent({ queue: 'errors', payload: { code: e.code, message: e.message,
              conversationId: String(frame.conversationId), clientMessageId: String(frame.clientMessageId) } });
          }
        }
      };
      return true;
    },
  };
}

function currentUser(): DemoUser | null {
  const id = tabUserId();
  return id ? server!.db().users.find((u) => u.id === id && u.status === 'ACTIVE') ?? null : null;
}

// ---------- rules ----------

type Access = { side: ChatSide | null; canWrite: boolean };

function vendorOf(db: DemoDB, vendorId: string) {
  return db.vendors.find((v) => v.id === vendorId) ?? null;
}

function access(user: DemoUser, c: DemoConversation): Access {
  const db = server!.db();
  if (c.buyerId === user.id) return { side: 'BUYER', canWrite: c.status === 'OPEN' };
  const v = vendorOf(db, c.vendorId);
  if (v && v.ownerUserId === user.id) return { side: 'VENDOR', canWrite: c.status === 'OPEN' && v.status === 'APPROVED' };
  if (user.roles.includes('ADMIN')) return { side: null, canWrite: false };
  throw notFound('Conversation');
}

function writable(user: DemoUser, c: DemoConversation): ChatSide {
  const a = access(user, c);
  if (!a.side) throw forbidden('Administrators can read conversations but not take part in them');
  if (!a.canWrite) {
    if (c.status !== 'OPEN') throw conflict('conversation_closed', 'This conversation has been closed');
    throw forbidden('This shop cannot send messages while it is not active');
  }
  return a.side;
}

function conversation(id: string) {
  const c = chat().conversations.find((x) => x.id === id);
  if (!c) throw notFound('Conversation');
  return c;
}

const sent = new Map<string, number[]>();
function rateLimited(userId: string, now: number) {
  const recent = (sent.get(userId) ?? []).filter((t) => t > now - 10_000);
  if (recent.length >= 20) return true;
  recent.push(now);
  sent.set(userId, recent);
  return false;
}

const lastTyping = new Map<string, number>();

function participantsOf(conversationId: string, side?: ChatSide, skipMuted = false) {
  return chat().participants.filter((p) => p.conversationId === conversationId && (!side || p.side === side) && !(skipMuted && p.muted));
}

function view(m: DemoChatMessage, reveal = false): ChatMessage {
  const hide = !!m.deletedAt && !reveal;
  return {
    id: m.id, conversationId: m.conversationId, senderId: m.senderId, senderRole: m.senderRole, type: m.type,
    body: hide ? null : m.body, attachmentUrl: hide ? null : m.attachmentUrl, metadata: hide ? null : m.metadata,
    clientMessageId: m.clientMessageId, createdAt: m.createdAt, editedAt: m.editedAt, deleted: !!m.deletedAt,
  };
}

function checkedText(user: DemoUser, c: DemoConversation, raw: unknown, required: boolean): string | null {
  let text: string | null;
  try {
    text = normaliseText(raw, required);
  } catch (e) {
    throw bad('invalid_message', (e as Error).message);
  }
  const found = violations(text);
  if (found.length) {
    chat().violations.push({ conversationId: c.id, userId: user.id, kinds: found.sort().join(','), createdAt: iso(Date.now()) });
    server!.save(); // recorded even though the message is refused
    throw new ChatHttpError(422, 'contact_info_not_allowed', REFUSAL);
  }
  return text;
}

function itemsSummary(o: DemoOrder, suborderId: string): string {
  const s = o.suborders.find((x) => x.id === suborderId);
  if (!s || s.items.length === 0) return '';
  const head = `${s.items[0]!.productName} ×${s.items[0]!.quantity}`;
  const rest = s.items.length - 1;
  return rest === 0 ? head : `${head} and ${rest} more item${rest === 1 ? '' : 's'}`;
}

function findSuborder(db: DemoDB, suborderId: string) {
  for (const o of db.orders) {
    const s = o.suborders.find((x) => x.id === suborderId);
    if (s) return { o, s };
  }
  return null;
}

/** Same steps as chat-service MessageService.send: idempotency first, then rate limit, then content. */
export function sendMessage(user: DemoUser, conversationId: string, b: Record<string, unknown>): { message: ChatMessage; duplicate: boolean } {
  const db = server!.db();
  const c = conversation(conversationId);
  const side = writable(user, c);
  const type = String(b.type ?? '') as ChatMessageType;
  if (!['TEXT', 'IMAGE', 'PRODUCT_CARD', 'ORDER_CARD'].includes(type)) throw bad('invalid_type', 'This kind of message cannot be sent');
  const clientMessageId = typeof b.clientMessageId === 'string' ? b.clientMessageId : '';
  if (!clientMessageId) throw bad('validation_failed', 'clientMessageId is required');
  const existing = chat().messages.find((m) => m.senderId === user.id && m.clientMessageId === clientMessageId);
  if (existing) {
    if (existing.conversationId !== c.id) throw conflict('client_message_id_reused', 'This client message id was already used in another conversation');
    deliver([user.id], { queue: 'messages', payload: { kind: 'MESSAGE_CREATED', conversationId: c.id, message: view(existing) } });
    return { message: view(existing), duplicate: true };
  }
  const now = Date.now();
  if (rateLimited(user.id, now)) throw new ChatHttpError(429, 'too_many_requests', 'You are sending messages too quickly. Wait a few seconds and try again.');

  let body: string | null;
  let attachmentUrl: string | null = null;
  let metadata: Record<string, unknown> | null = null;
  if (type === 'TEXT') {
    body = checkedText(user, c, b.body, true);
  } else if (type === 'IMAGE') {
    const a = chat().attachments.find((x) => x.id === b.attachmentId && x.conversationId === c.id && x.uploaderId === user.id);
    if (!a) throw notFound('Attachment');
    body = checkedText(user, c, b.body, false);
    attachmentUrl = `/api/v1/chat/attachments/${a.id}`;
    metadata = { attachmentId: a.id, contentType: a.contentType };
  } else if (type === 'PRODUCT_CARD') {
    const p = db.products.find((x) => x.id === b.productId && x.vendorId === c.vendorId);
    if (!p) throw notFound('Product');
    const prices = p.variants.filter((v) => v.active).map((v) => v.price);
    body = checkedText(user, c, b.body, false);
    metadata = { productId: p.id, name: p.name, slug: p.slug, imageUrl: p.images[0]?.url ?? null, price: prices.length ? Math.min(...prices) : null, currency: 'USD' };
  } else {
    const f = typeof b.suborderId === 'string' ? findSuborder(db, b.suborderId) : null;
    if (!f || f.o.customerId !== c.buyerId || f.s.vendorId !== c.vendorId) throw notFound('Order');
    body = checkedText(user, c, b.body, false);
    metadata = { orderId: f.o.id, suborderId: f.s.id, status: f.s.status, itemsSummary: itemsSummary(f.o, f.s.id), total: f.s.total, currency: 'USD' };
  }

  const m: DemoChatMessage = { id: chat().seq++, conversationId: c.id, senderId: user.id, senderRole: side, type, body, attachmentUrl,
    metadata, clientMessageId, createdAt: iso(now), editedAt: null, deletedAt: null };
  chat().messages.push(m);
  c.lastMessageId = m.id;
  c.lastMessageAt = m.createdAt;
  bumpRead(c.id, user.id, m.id);
  deliver(participantsOf(c.id).map((p) => p.userId), { queue: 'messages', payload: { kind: 'MESSAGE_CREATED', conversationId: c.id, message: view(m) } });
  return { message: view(m), duplicate: false };
}

function bumpRead(conversationId: string, userId: string, messageId: number) {
  const p = chat().participants.find((x) => x.conversationId === conversationId && x.userId === userId);
  if (p) p.lastRead = Math.max(p.lastRead, messageId);
  return p?.lastRead ?? 0;
}

function sideRead(conversationId: string, side: ChatSide) {
  return participantsOf(conversationId, side).reduce((max, p) => Math.max(max, p.lastRead), 0);
}

export function markRead(user: DemoUser, conversationId: string, lastReadMessageId: number | null): number {
  const c = conversation(conversationId);
  const a = access(user, c);
  if (!a.side) throw forbidden('Administrators can read conversations but not take part in them');
  const newest = c.lastMessageId ?? 0;
  const target = lastReadMessageId == null || !Number.isFinite(lastReadMessageId) ? newest : Math.min(lastReadMessageId, newest);
  const v = bumpRead(c.id, user.id, target);
  deliver(participantsOf(c.id).map((p) => p.userId), { queue: 'receipts', payload: { conversationId: c.id, side: a.side, lastReadMessageId: sideRead(c.id, a.side) } });
  return v;
}

function typing(user: DemoUser, conversationId: string, isTyping: boolean) {
  const c = conversation(conversationId);
  const a = access(user, c);
  if (!a.side || !a.canWrite) return;
  const key = `${user.id}:${c.id}`;
  if (isTyping) {
    const last = lastTyping.get(key) ?? 0;
    if (Date.now() - last < 2000) return;
    lastTyping.set(key, Date.now());
  }
  const name = a.side === 'BUYER' ? c.buyerName : vendorOf(server!.db(), c.vendorId)?.businessName ?? 'Seller';
  deliver(participantsOf(c.id, a.side === 'BUYER' ? 'VENDOR' : 'BUYER').map((p) => p.userId),
    { queue: 'typing', payload: { conversationId: c.id, side: a.side, name, typing: isTyping } });
}

/** SYSTEM message into every conversation about this suborder (order confirmed, shipped, delivered, cancelled). */
export function postOrderUpdate(db: DemoDB, suborderId: string, text: string, metadata: Record<string, unknown>, at: number) {
  for (const c of db.chat.conversations.filter((x) => x.suborderId === suborderId)) {
    const m: DemoChatMessage = { id: db.chat.seq++, conversationId: c.id, senderId: null, senderRole: 'SYSTEM', type: 'SYSTEM', body: text,
      attachmentUrl: null, metadata, clientMessageId: null, createdAt: iso(at), editedAt: null, deletedAt: null };
    db.chat.messages.push(m);
    c.lastMessageId = m.id;
    c.lastMessageAt = m.createdAt;
    deliver(db.chat.participants.filter((p) => p.conversationId === c.id).map((p) => p.userId),
      { queue: 'messages', payload: { kind: 'MESSAGE_CREATED', conversationId: c.id, message: view(m) } });
  }
}

const CANCEL_REASONS: Record<string, string> = {
  CUSTOMER_REQUEST: "at the buyer's request", OUT_OF_STOCK: 'because an item was out of stock',
  PAYMENT_DECLINED: 'because the payment was declined', CAPTURE_FAILED: 'because the payment could not be completed',
};

export function orderEvent(db: DemoDB, o: DemoOrder, event: 'CONFIRMED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED', at: number, suborderId?: string) {
  const subs = suborderId ? o.suborders.filter((s) => s.id === suborderId) : o.suborders;
  for (const s of subs) {
    const meta = { event: event === 'CONFIRMED' ? 'ORDER_CONFIRMED' : event === 'CANCELLED' ? 'ORDER_CANCELLED' : event, orderId: o.id, suborderId: s.id };
    const text = event === 'CONFIRMED' ? 'Order confirmed. The shop is preparing it.'
      : event === 'SHIPPED' ? `Shipped${s.carrier ? ` with ${s.carrier}` : ''}.${s.trackingNumber ? ` Tracking number: ${s.trackingNumber}` : ''}`
        : event === 'DELIVERED' ? 'Delivered. Enjoy your purchase! You can now review the items.'
          : `Order cancelled${CANCEL_REASONS[o.cancelReason ?? ''] ? ` ${CANCEL_REASONS[o.cancelReason ?? '']}` : ''}.${o.confirmedAt ? ' Any payment is being refunded.' : ''}`;
    postOrderUpdate(db, s.id, text, event === 'SHIPPED' ? { ...meta, carrier: s.carrier, trackingNumber: s.trackingNumber } : meta, at);
  }
}

// ---------- views ----------

function medianReplySeconds(vendorId: string): number | null {
  const c = chat();
  const convs = new Set(c.conversations.filter((x) => x.vendorId === vendorId).map((x) => x.id));
  const waits: number[] = [];
  for (const id of convs) {
    const people = c.messages.filter((m) => m.conversationId === id && m.senderRole !== 'SYSTEM').sort((a, b) => a.id - b.id);
    people.forEach((q, i) => {
      if (q.senderRole !== 'BUYER' || (i > 0 && people[i - 1]!.senderRole === 'BUYER')) return;
      const answer = people.slice(i + 1).find((m) => m.senderRole === 'VENDOR');
      if (answer) waits.push((Date.parse(answer.createdAt) - Date.parse(q.createdAt)) / 1000);
    });
  }
  if (waits.length < 3) return null;
  waits.sort((a, b) => a - b);
  const mid = waits.length / 2;
  return Math.max(1, Math.round(waits.length % 2 ? waits[Math.floor(mid)]! : (waits[mid - 1]! + waits[mid]!) / 2));
}

function counterpart(c: DemoConversation, viewerSide: ChatSide) {
  const db = server!.db();
  if (viewerSide === 'BUYER') {
    const v = vendorOf(db, c.vendorId);
    const sellers = participantsOf(c.id, 'VENDOR').map((p) => p.userId);
    const online = sellers.some(isOnline);
    const seen = sellers.map((u) => chat().lastSeen[u]).filter(Boolean).sort().pop() ?? null;
    return { kind: 'SHOP' as const, name: v?.businessName ?? 'Shop', vendorId: c.vendorId, vendorSlug: v?.slug ?? null, userIds: sellers,
      online, lastSeenAt: online ? null : seen, typicalReplySeconds: medianReplySeconds(c.vendorId) };
  }
  const online = isOnline(c.buyerId);
  return { kind: 'BUYER' as const, name: c.buyerName, vendorId: null, vendorSlug: null, userIds: [c.buyerId], online,
    lastSeenAt: online ? null : chat().lastSeen[c.buyerId] ?? null, typicalReplySeconds: null };
}

function context(c: DemoConversation) {
  const db = server!.db();
  if (c.productId) {
    const p = db.products.find((x) => x.id === c.productId);
    const prices = p?.variants.filter((v) => v.active).map((v) => v.price) ?? [];
    return { kind: 'PRODUCT' as const, productId: c.productId, productName: p?.name ?? 'Product', productImageUrl: p?.images[0]?.url ?? null,
      price: prices.length ? Math.min(...prices) : null, currency: 'USD' };
  }
  if (c.suborderId) {
    const f = findSuborder(db, c.suborderId);
    const first = f?.s.items[0];
    const image = first ? db.products.find((p) => p.id === first.productId)?.images[0]?.url ?? null : null;
    return { kind: 'ORDER' as const, orderId: f?.o.id ?? null, suborderId: c.suborderId, orderStatus: f?.s.status ?? null,
      itemsSummary: f ? itemsSummary(f.o, f.s.id) : '', orderTotal: f?.s.total ?? null, currency: 'USD', productImageUrl: image };
  }
  return { kind: 'GENERAL' as const };
}

function conversationView(user: DemoUser, c: DemoConversation): Conversation {
  const a = access(user, c);
  const viewSide = a.side ?? 'BUYER';
  const mine = chat().participants.find((p) => p.conversationId === c.id && p.userId === user.id);
  return {
    id: c.id, side: a.side, status: c.status, buyerId: c.buyerId, buyerName: c.buyerName, vendorId: c.vendorId,
    vendorName: vendorOf(server!.db(), c.vendorId)?.businessName ?? 'Shop', context: context(c), counterpart: counterpart(c, viewSide),
    myLastReadMessageId: mine?.lastRead ?? 0, theirLastReadMessageId: sideRead(c.id, viewSide === 'BUYER' ? 'VENDOR' : 'BUYER'),
    muted: mine?.muted ?? false, canWrite: a.canWrite, createdAt: c.createdAt, lastMessageAt: c.lastMessageAt,
  };
}

function unreadFor(conversationId: string, userId: string, lastRead: number) {
  return Math.min(100, chat().messages.filter((m) => m.conversationId === conversationId && m.id > lastRead && m.senderId !== userId && !m.deletedAt).length);
}

function preview(m: DemoChatMessage) {
  const text = m.deletedAt ? 'Message deleted'
    : m.type === 'IMAGE' ? (m.body ? `Photo: ${m.body}` : 'Photo')
      : m.type === 'PRODUCT_CARD' ? (m.body || 'Shared a product') : m.type === 'ORDER_CARD' ? (m.body || 'Shared an order') : m.body ?? '';
  return { id: m.id, type: m.type, text: text.length > 140 ? `${text.slice(0, 139)}…` : text, senderRole: m.senderRole, deleted: !!m.deletedAt };
}

function inbox(user: DemoUser, side: ChatSide, cursor: string | null, limit: number) {
  const mine = chat().participants.filter((p) => p.userId === user.id && p.side === side);
  const rows = mine.map((p) => ({ p, c: chat().conversations.find((x) => x.id === p.conversationId)! }))
    .filter((r) => r.c && r.c.lastMessageId !== null)
    .sort((a, b) => b.c.lastMessageAt.localeCompare(a.c.lastMessageAt) || b.c.id.localeCompare(a.c.id));
  const start = cursor ? rows.findIndex((r) => `${r.c.lastMessageAt}|${r.c.id}` === cursor) + 1 : 0;
  const page = rows.slice(start, start + limit);
  const items: ConversationSummary[] = page.map(({ p, c }) => {
    const last = chat().messages.find((m) => m.id === c.lastMessageId)!;
    return { id: c.id, status: c.status, counterpart: counterpart(c, side), context: context(c), lastMessage: preview(last),
      unread: p.muted ? 0 : unreadFor(c.id, user.id, p.lastRead), muted: p.muted, lastMessageAt: c.lastMessageAt };
  });
  const more = start + limit < rows.length;
  return { items, nextCursor: more && page.length ? `${page[page.length - 1]!.c.lastMessageAt}|${page[page.length - 1]!.c.id}` : null };
}

// ---------- HTTP routes ----------

export interface RouteApi {
  GET(p: string, h: (r: ChatReq) => unknown): void;
  POST(p: string, h: (r: ChatReq) => unknown): void;
  PUT(p: string, h: (r: ChatReq) => unknown): void;
  PATCH(p: string, h: (r: ChatReq) => unknown): void;
  DEL(p: string, h: (r: ChatReq) => unknown): void;
}

export interface ChatReq {
  query: URLSearchParams;
  body: unknown;
  form: FormData | null;
  user: DemoUser | null;
  params: string[];
  now: number;
}

function requireUser(r: ChatReq): DemoUser {
  if (!r.user) throw new ChatHttpError(401, 'unauthorized', 'Sign in to continue');
  return r.user;
}

function myVendor(user: DemoUser) {
  if (!user.roles.includes('VENDOR')) throw forbidden();
  const v = server!.db().vendors.find((x) => x.ownerUserId === user.id);
  if (!v) throw forbidden('Only sellers have a shop inbox');
  return v;
}

const B = (r: ChatReq) => (r.body ?? {}) as Record<string, unknown>;
const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

export function registerChatRoutes(api: RouteApi) {
  api.POST('/api/v1/chat/conversations', (r) => {
    const user = requireUser(r);
    const db = server!.db();
    const b = B(r);
    const productId = typeof b.productId === 'string' ? b.productId : null;
    const suborderId = typeof b.suborderId === 'string' ? b.suborderId : null;
    if (productId && suborderId) throw bad('invalid_context', 'A conversation is about a product or an order, not both');
    const v = db.vendors.find((x) => x.id === b.vendorId && x.status === 'APPROVED');
    if (!v) throw notFound('Shop');
    if (v.ownerUserId === user.id) throw new ChatHttpError(422, 'own_shop', 'You cannot start a conversation with your own shop');
    if (productId && !db.products.some((p) => p.id === productId && p.vendorId === v.id && p.status === 'PUBLISHED')) throw notFound('Product');
    if (suborderId) {
      const f = findSuborder(db, suborderId);
      if (!f || f.o.customerId !== user.id || f.s.vendorId !== v.id) throw notFound('Order');
    }
    let c = chat().conversations.find((x) => x.buyerId === user.id && x.vendorId === v.id && x.productId === productId && x.suborderId === suborderId);
    const created = !c;
    if (!c) {
      const parts = user.fullName.trim().split(/\s+/);
      c = { id: newId(), buyerId: user.id, buyerName: parts.length > 1 ? `${parts[0]} ${parts[parts.length - 1]![0]}.` : parts[0] ?? 'Customer',
        vendorId: v.id, productId, suborderId, status: 'OPEN', lastMessageAt: iso(r.now), lastMessageId: null, createdAt: iso(r.now) };
      chat().conversations.push(c);
      chat().participants.push({ conversationId: c.id, userId: user.id, side: 'BUYER', lastRead: 0, muted: false },
        { conversationId: c.id, userId: v.ownerUserId, side: 'VENDOR', lastRead: 0, muted: false });
    }
    return response(created ? 201 : 200, conversationView(user, c));
  });

  api.GET('/api/v1/chat/conversations', (r) => inbox(requireUser(r), 'BUYER', r.query.get('cursor'), Number(r.query.get('limit') ?? 20)));
  api.GET('/api/v1/vendor/chat/conversations', (r) => {
    const user = requireUser(r);
    myVendor(user);
    return inbox(user, 'VENDOR', r.query.get('cursor'), Number(r.query.get('limit') ?? 20));
  });
  api.GET('/api/v1/chat/unread-count', (r) => {
    const user = requireUser(r);
    const count = (side: ChatSide) => chat().participants.filter((p) => p.userId === user.id && p.side === side && !p.muted)
      .reduce((sum, p) => sum + unreadFor(p.conversationId, user.id, p.lastRead), 0);
    return { asBuyer: count('BUYER'), asVendor: count('VENDOR') };
  });
  api.GET('/api/v1/chat/conversations/:id', (r) => conversationView(requireUser(r), conversation(r.params[0]!)));
  api.GET('/api/v1/chat/conversations/:id/messages', (r) => {
    const user = requireUser(r);
    const c = conversation(r.params[0]!);
    const a = access(user, c);
    const reveal = !a.side;
    const limit = Math.min(100, Math.max(1, Number(r.query.get('limit') ?? 30)));
    const all = chat().messages.filter((m) => m.conversationId === c.id).sort((x, y) => x.id - y.id);
    const after = r.query.get('after');
    if (after !== null) {
      const newer = all.filter((m) => m.id > Number(after));
      const since = r.query.get('since');
      const changed = since ? all.filter((m) => m.id <= Number(after) && ((m.editedAt && m.editedAt > since) || (m.deletedAt && m.deletedAt > since))) : [];
      return { items: newer.slice(0, limit).map((m) => view(m, reveal)), hasMore: newer.length > limit, changed: changed.map((m) => view(m, reveal)) };
    }
    const before = r.query.get('before');
    const older = before ? all.filter((m) => m.id < Number(before)) : all;
    const page = older.slice(Math.max(0, older.length - limit));
    return { items: page.map((m) => view(m, reveal)), hasMore: older.length > limit };
  });
  api.POST('/api/v1/chat/conversations/:id/messages', (r) => {
    const s = sendMessage(requireUser(r), r.params[0]!, B(r));
    return response(s.duplicate ? 200 : 201, s.message);
  });
  api.POST('/api/v1/chat/conversations/:id/read', (r) => {
    const b = B(r);
    return { lastReadMessageId: markRead(requireUser(r), r.params[0]!, typeof b.lastReadMessageId === 'number' ? b.lastReadMessageId : null) };
  });
  api.PUT('/api/v1/chat/conversations/:id/mute', (r) => {
    const user = requireUser(r);
    const c = conversation(r.params[0]!);
    if (!access(user, c).side) throw forbidden();
    const p = chat().participants.find((x) => x.conversationId === c.id && x.userId === user.id);
    if (p) p.muted = B(r).muted === true;
    return conversationView(user, c);
  });
  api.POST('/api/v1/chat/conversations/:id/report', (r) => {
    const user = requireUser(r);
    const c = conversation(r.params[0]!);
    const a = access(user, c);
    if (!a.side) throw forbidden('Only people in the conversation can report it');
    if (chat().reports.some((x) => x.conversationId === c.id && x.reporterId === user.id && x.status === 'OPEN')) {
      throw conflict('already_reported', 'You already reported this conversation; our team is reviewing it');
    }
    const b = B(r);
    const report = { id: newId(), conversationId: c.id, reporterId: user.id, reporterSide: a.side, reason: String(b.reason ?? 'OTHER'),
      details: typeof b.details === 'string' && b.details.trim() ? b.details.trim().slice(0, 1000) : null, status: 'OPEN' as const,
      resolution: null, resolutionNote: null, resolvedAt: null, createdAt: iso(r.now) };
    chat().reports.push(report);
    return response(201, reportView(report));
  });
  const changeable = (user: DemoUser, id: number, edit: boolean) => {
    const m = chat().messages.find((x) => x.id === id);
    if (!m) throw notFound('Message');
    writable(user, conversation(m.conversationId));
    if (m.senderId !== user.id) throw forbidden('You can only change your own messages');
    if (m.deletedAt) throw conflict('message_deleted', 'This message was deleted');
    if (edit && m.type !== 'TEXT') throw new ChatHttpError(422, 'not_editable', 'Only text messages can be edited');
    if (Date.now() - Date.parse(m.createdAt) > EDIT_WINDOW_MS) throw conflict('edit_window_passed', 'Messages can only be changed for 10 minutes after sending');
    return m;
  };
  const broadcastUpdate = (m: DemoChatMessage) => {
    deliver(participantsOf(m.conversationId).map((p) => p.userId), { queue: 'messages', payload: { kind: 'MESSAGE_UPDATED', conversationId: m.conversationId, message: view(m) } });
    return view(m);
  };
  api.PATCH('/api/v1/chat/messages/:id', (r) => {
    const user = requireUser(r);
    const m = changeable(user, Number(r.params[0]), true);
    m.body = checkedText(user, conversation(m.conversationId), B(r).body, true);
    m.editedAt = iso(r.now);
    return broadcastUpdate(m);
  });
  api.DEL('/api/v1/chat/messages/:id', (r) => {
    const m = changeable(requireUser(r), Number(r.params[0]), false);
    m.deletedAt = iso(r.now);
    return broadcastUpdate(m);
  });

  api.POST('/api/v1/chat/attachments', async (r) => {
    const user = requireUser(r);
    const c = conversation(r.query.get('conversationId') ?? '');
    writable(user, c);
    const file = r.form?.get('file');
    if (!(file instanceof Blob) || file.size === 0) throw bad('invalid_image', 'Choose an image to upload');
    if (file.size > 5 * 1024 * 1024) throw bad('image_too_large', 'Images can be at most 5 MB');
    const head = new Uint8Array(await file.slice(0, 12).arrayBuffer());
    const type = head[0] === 0x89 && head[1] === 0x50 && head[2] === 0x4e && head[3] === 0x47 ? 'image/png'
      : head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff ? 'image/jpeg'
        : head[0] === 0x52 && head[1] === 0x49 && head[8] === 0x57 && head[9] === 0x45 ? 'image/webp' : null;
    if (!type) throw bad('unsupported_image', 'Only PNG, JPEG and WebP images can be sent');
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const fr = new FileReader();
      fr.onload = () => resolve(String(fr.result));
      fr.onerror = () => reject(new Error('read failed'));
      fr.readAsDataURL(file);
    });
    const id = newId();
    chat().attachments.push({ id, conversationId: c.id, uploaderId: user.id, contentType: type, dataUrl });
    return response(201, { id, url: `/api/v1/chat/attachments/${id}`, contentType: type, sizeBytes: file.size });
  });
  api.GET('/api/v1/chat/attachments/:id', async (r) => {
    const user = requireUser(r);
    const a = chat().attachments.find((x) => x.id === r.params[0]);
    if (!a) throw notFound('Attachment');
    access(user, conversation(a.conversationId));
    const blob = await (await fetchDataUrl(a.dataUrl)).blob();
    return new Response(blob, { status: 200, headers: { 'Content-Type': a.contentType } });
  });

  api.GET('/api/v1/vendor/chat/quick-replies', (r) => {
    const v = myVendor(requireUser(r));
    return chat().quickReplies.filter((q) => q.vendorId === v.id).sort((a, b) => a.sortOrder - b.sortOrder).map(({ vendorId: _v, ...q }) => q);
  });
  api.POST('/api/v1/vendor/chat/quick-replies', (r) => {
    const v = myVendor(requireUser(r));
    const b = B(r);
    const title = String(b.title ?? '').trim();
    const body = String(b.body ?? '').trim();
    if (!title || title.length > 60 || !body || body.length > 1000) throw bad('validation_failed', 'A title (up to 60 characters) and a reply are required');
    const q = { id: newId(), vendorId: v.id, title, body, sortOrder: Number(b.sortOrder ?? 0) };
    chat().quickReplies.push(q);
    return response(201, { id: q.id, title: q.title, body: q.body, sortOrder: q.sortOrder });
  });
  api.DEL('/api/v1/vendor/chat/quick-replies/:id', (r) => {
    const v = myVendor(requireUser(r));
    const i = chat().quickReplies.findIndex((q) => q.id === r.params[0] && q.vendorId === v.id);
    if (i < 0) throw notFound('Quick reply');
    chat().quickReplies.splice(i, 1);
    return undefined;
  });

  api.GET('/api/v1/admin/chat/reports', (r) => {
    const user = requireUser(r);
    if (!user.roles.includes('ADMIN')) throw forbidden();
    const status = r.query.get('status');
    const list = chat().reports.filter((x) => !status || x.status === status).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return { items: list.map(reportView), page: 0, size: list.length, totalItems: list.length, totalPages: 1 };
  });
  api.POST('/api/v1/admin/chat/reports/:id/resolve', (r) => {
    const user = requireUser(r);
    if (!user.roles.includes('ADMIN')) throw forbidden();
    const report = chat().reports.find((x) => x.id === r.params[0]);
    if (!report) throw notFound('Report');
    if (report.status !== 'OPEN') throw conflict('already_resolved', 'This report was already resolved');
    const b = B(r);
    const block = b.action === 'BLOCK_CONVERSATION';
    Object.assign(report, { status: 'RESOLVED', resolution: block ? 'CONVERSATION_BLOCKED' : 'DISMISSED',
      resolutionNote: typeof b.note === 'string' ? b.note : null, resolvedAt: iso(r.now) });
    const c = conversation(report.conversationId);
    if (block && c.status !== 'BLOCKED') {
      c.status = 'BLOCKED';
      const db = server!.db();
      const m: DemoChatMessage = { id: db.chat.seq++, conversationId: c.id, senderId: null, senderRole: 'SYSTEM', type: 'SYSTEM',
        body: 'This conversation was closed by MarketFlow support after a report.', attachmentUrl: null,
        metadata: { event: 'CONVERSATION_BLOCKED' }, clientMessageId: null, createdAt: iso(r.now), editedAt: null, deletedAt: null };
      db.chat.messages.push(m);
      c.lastMessageId = m.id;
      c.lastMessageAt = m.createdAt;
      const people = participantsOf(c.id).map((p) => p.userId);
      deliver(people, { queue: 'messages', payload: { kind: 'MESSAGE_CREATED', conversationId: c.id, message: view(m) } });
      deliver(people, { queue: 'messages', payload: { kind: 'CONVERSATION_UPDATED', conversationId: c.id, conversationStatus: 'BLOCKED' } });
    }
    return reportView(report);
  });
}

function reportView(x: DemoChat['reports'][number]) {
  const db = server!.db();
  const c = db.chat.conversations.find((y) => y.id === x.conversationId);
  return { ...x, vendorName: c ? vendorOf(db, c.vendorId)?.businessName ?? 'Shop' : 'Shop', buyerName: c?.buyerName ?? 'Customer',
    violations: db.chat.violations.filter((v) => v.conversationId === x.conversationId).length };
}

async function fetchDataUrl(dataUrl: string): Promise<Response> {
  const [head, data] = dataUrl.split(',', 2);
  const type = head!.slice(5).split(';')[0]!;
  const bytes = Uint8Array.from(atob(data!), (ch) => ch.charCodeAt(0));
  return new Response(new Blob([bytes], { type }));
}

// ---------- sample conversations ----------

/** Realistic conversations for the sample shops and customers, including one tied to an order. */
export function seedChats(db: DemoDB, now: number) {
  const ch = db.chat;
  const minute = 60_000;
  const user = (email: string) => db.users.find((u) => u.email === email)!;
  const ada = user('ada@demo.marketflow.local');
  const linus = user('linus@demo.marketflow.local');
  const vendorBy = (email: string) => db.vendors.find((v) => v.ownerUserId === user(email).id)!;
  const aurora = vendorBy('aurora@demo.marketflow.local');
  const kitchen = vendorBy('kitchenco@demo.marketflow.local');
  const product = (vendorId: string, i = 0) => db.products.filter((p) => p.vendorId === vendorId)[i]!;

  const open = (buyer: DemoUser, vendor: typeof aurora, ctx: { productId?: string; suborderId?: string }, startedAgo: number) => {
    const parts = buyer.fullName.split(' ');
    const c: DemoConversation = { id: newId(), buyerId: buyer.id, buyerName: `${parts[0]} ${parts[1]![0]}.`, vendorId: vendor.id,
      productId: ctx.productId ?? null, suborderId: ctx.suborderId ?? null, status: 'OPEN', lastMessageAt: iso(now - startedAgo),
      lastMessageId: null, createdAt: iso(now - startedAgo) };
    ch.conversations.push(c);
    ch.participants.push({ conversationId: c.id, userId: buyer.id, side: 'BUYER', lastRead: 0, muted: false },
      { conversationId: c.id, userId: vendor.ownerUserId, side: 'VENDOR', lastRead: 0, muted: false });
    return c;
  };
  const say = (c: DemoConversation, side: ChatSide | 'SYSTEM', ago: number, body: string, extra: Partial<DemoChatMessage> = {}) => {
    const senderId = side === 'BUYER' ? c.buyerId : side === 'VENDOR' ? db.vendors.find((v) => v.id === c.vendorId)!.ownerUserId : null;
    const m: DemoChatMessage = { id: ch.seq++, conversationId: c.id, senderId, senderRole: side, type: side === 'SYSTEM' ? 'SYSTEM' : 'TEXT', body,
      attachmentUrl: null, metadata: null, clientMessageId: senderId ? newId() : null, createdAt: iso(now - ago), editedAt: null, deletedAt: null, ...extra };
    ch.messages.push(m);
    c.lastMessageId = m.id;
    c.lastMessageAt = m.createdAt;
    if (senderId) ch.participants.filter((p) => p.conversationId === c.id && p.userId === senderId).forEach((p) => { p.lastRead = m.id; });
    return m;
  };
  const readAll = (c: DemoConversation, side: ChatSide) => ch.participants
    .filter((p) => p.conversationId === c.id && p.side === side).forEach((p) => { p.lastRead = c.lastMessageId ?? 0; });

  // 1. Ada asks Aurora about the headphones; answered within about two hours, read by both
  const headphones = product(aurora.id, 0);
  const c1 = open(ada, aurora, { productId: headphones.id }, 3 * 1440 * minute);
  say(c1, 'BUYER', 3 * 1440 * minute, `Hi! Do the ${headphones.name} work with a phone, or only with an audio interface?`, { type: 'PRODUCT_CARD',
    metadata: { productId: headphones.id, name: headphones.name, slug: headphones.slug, imageUrl: headphones.images[0]?.url ?? null, price: Math.min(...headphones.variants.map((v) => v.price)), currency: 'USD' } });
  say(c1, 'VENDOR', 3 * 1440 * minute - 95 * minute, 'Hi Ada! They work great with phones: the impedance is low enough, and the cable has a 3.5 mm jack with a 6.35 mm adapter in the box.');
  say(c1, 'BUYER', 3 * 1440 * minute - 110 * minute, 'Perfect. And are the ear cushions replaceable?');
  say(c1, 'VENDOR', 3 * 1440 * minute - 230 * minute, 'Yes, they twist off. Replacement cushions are $19 a pair, just message us when you need them.');
  say(c1, 'BUYER', 2 * 1440 * minute, 'Thanks, ordering the Matte Black ones!');
  say(c1, 'VENDOR', 2 * 1440 * minute - 70 * minute, 'Great choice. We will pack them today.');
  readAll(c1, 'BUYER');
  readAll(c1, 'VENDOR');

  // 2. A conversation about one of Ada's orders, with the order's status updates as SYSTEM messages
  const adaOrders = db.orders.filter((o) => o.customerId === ada.id && o.status !== 'CANCELLED')
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const pick = adaOrders.flatMap((o) => o.suborders.map((s) => ({ o, s })))
    .sort((a, b) => Number(b.s.vendorId === aurora.id) - Number(a.s.vendorId === aurora.id)
      || ['CONFIRMED', 'SHIPPED', 'DELIVERED'].indexOf(a.s.status) - ['CONFIRMED', 'SHIPPED', 'DELIVERED'].indexOf(b.s.status))[0];
  if (pick) {
    const vendor = db.vendors.find((v) => v.id === pick.s.vendorId)!;
    const confirmedAt = Date.parse(pick.o.confirmedAt ?? pick.o.createdAt);
    const c2 = open(ada, vendor, { suborderId: pick.s.id }, now - confirmedAt);
    const sys = (at: number, event: string, text: string, meta: Record<string, unknown> = {}) =>
      say(c2, 'SYSTEM', now - at, text, { metadata: { event, orderId: pick.o.id, suborderId: pick.s.id, ...meta } });
    sys(confirmedAt, 'ORDER_CONFIRMED', 'Order confirmed. The shop is preparing it.');
    say(c2, 'BUYER', now - confirmedAt - 20 * minute, 'Hi, could you gift-wrap this one? It is a birthday present.', {
      type: 'ORDER_CARD', metadata: { orderId: pick.o.id, suborderId: pick.s.id, status: 'CONFIRMED', itemsSummary: itemsSummary(pick.o, pick.s.id), total: pick.s.total, currency: 'USD' },
    });
    say(c2, 'VENDOR', now - confirmedAt - 75 * minute, 'Of course! Gift wrap is on us, and we will leave the invoice out of the parcel.');
    if (pick.s.shippedAt) {
      sys(Date.parse(pick.s.shippedAt), 'SHIPPED', `Shipped with ${pick.s.carrier}. Tracking number: ${pick.s.trackingNumber}`,
        { carrier: pick.s.carrier, trackingNumber: pick.s.trackingNumber });
    }
    if (pick.s.deliveredAt) {
      sys(Date.parse(pick.s.deliveredAt), 'DELIVERED', 'Delivered. Enjoy your purchase! You can now review the items.');
      say(c2, 'BUYER', now - Date.parse(pick.s.deliveredAt) - 50 * minute, 'Arrived beautifully wrapped. Thank you!');
    }
    readAll(c2, 'BUYER');
    readAll(c2, 'VENDOR');
  }

  // 3. Linus asks Aurora a general question: unread in the shop inbox
  const c3 = open(linus, aurora, {}, 40 * minute);
  say(c3, 'BUYER', 40 * minute, 'Hello! Do you ship to Canada, and how long does it usually take?');
  say(c3, 'BUYER', 38 * minute, 'Also, is there a warranty on the speakers?');

  // 4. Ada asks Kitchen & Co about a skillet: not answered yet
  const skillet = product(kitchen.id, 0);
  const c4 = open(ada, kitchen, { productId: skillet.id }, 5 * 60 * minute);
  say(c4, 'BUYER', 5 * 60 * minute, `Is the ${skillet.name} pre-seasoned, and does it work on induction?`);

  // shop templates for the seller hub
  [['Shipping times', 'We ship within 2 working days. Delivery takes 3–5 days in the US and 5–10 days internationally.'],
    ['Warranty', 'All our products come with a 2-year warranty. Message us here if anything goes wrong.'],
    ['Thanks', 'Thank you for your order! Let us know if there is anything else we can help with.']].forEach(([title, body], i) =>
    ch.quickReplies.push({ id: newId(), vendorId: aurora.id, title: title!, body: body!, sortOrder: i }));

  ch.lastSeen[aurora.ownerUserId] = iso(now - 25 * minute);
  ch.lastSeen[kitchen.ownerUserId] = iso(now - 9 * 60 * minute);
  ch.lastSeen[ada.id] = iso(now - 3 * 60 * minute);
  ch.lastSeen[linus.id] = iso(now - 38 * minute);
}

export { MAX_LENGTH };
