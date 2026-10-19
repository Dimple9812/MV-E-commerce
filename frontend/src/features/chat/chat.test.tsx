import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setSession } from '@/lib/api';
import type { ChatMessage, Conversation, ConversationSummary } from '@/lib/types';
import { FakeChatTransport, json, mockApi, renderApp, tokenFor } from '@/test/harness';
import { ChatWindow } from './ChatWindow';
import { ConversationList } from './Inbox';
import { setChatTransportFactory, backoffDelay } from './transport';
import { chatKeys, clearUnread } from './cache';
import { QueryClient } from '@tanstack/react-query';

const ME = tokenFor().user.id;
const SELLER = 'seller-1';

const conversation: Conversation = {
  id: 'c1', side: 'BUYER', status: 'OPEN', buyerId: ME, buyerName: 'Ada M.', vendorId: 'v1', vendorName: 'Aurora Sound',
  context: { kind: 'PRODUCT', productId: 'p1', productName: 'Studio Headphones', price: 139, currency: 'USD' },
  counterpart: { kind: 'SHOP', name: 'Aurora Sound', vendorId: 'v1', userIds: [SELLER], online: false, lastSeenAt: null, typicalReplySeconds: 7200 },
  myLastReadMessageId: 2, theirLastReadMessageId: 1, muted: false, canWrite: true,
  createdAt: '2026-10-19T09:00:00Z', lastMessageAt: '2026-10-19T09:05:00Z',
};

const msg = (id: number, from: 'BUYER' | 'VENDOR', body: string, extra: Partial<ChatMessage> = {}): ChatMessage => ({
  id, conversationId: 'c1', senderId: from === 'BUYER' ? ME : SELLER, senderRole: from, type: 'TEXT', body,
  createdAt: new Date(Date.now() - (10 - id) * 60_000).toISOString(), deleted: false, clientMessageId: `cm-${id}`, ...extra,
});

let transport: FakeChatTransport;

beforeEach(() => {
  transport = new FakeChatTransport();
  setChatTransportFactory(() => transport);
});
afterEach(() => { vi.restoreAllMocks(); setSession(null); });

function api(extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  return mockApi({
    'POST /api/v1/auth/refresh': () => json(200, tokenFor()),
    'GET /api/v1/chat/conversations/c1': () => json(200, conversation),
    'GET /api/v1/chat/conversations/c1/messages': () => json(200, { items: [msg(1, 'BUYER', 'Is it wireless?'), msg(2, 'VENDOR', 'Yes, Bluetooth 5.3')], hasMore: false }),
    'GET /api/v1/chat/unread-count': () => json(200, { asBuyer: 0, asVendor: 0 }),
    'GET /api/v1/chat/conversations': () => json(200, { items: [] }),
    ...extra,
  });
}

async function openWindow() {
  renderApp(<ChatWindow conversationId="c1" />, { path: '/chat', route: '/chat' });
  await screen.findByText('Yes, Bluetooth 5.3');
}

describe('chat window', () => {
  it('shows the conversation with context, reply time, seen ticks and a system message', async () => {
    api({
      'GET /api/v1/chat/conversations/c1/messages': () => json(200, { items: [
        msg(1, 'BUYER', 'Is it wireless?'), msg(2, 'VENDOR', 'Yes, Bluetooth 5.3'), msg(3, 'BUYER', 'Great, ordering now'),
        msg(4, 'VENDOR', 'Shipped with DHL', { senderRole: 'SYSTEM', senderId: null, type: 'SYSTEM' }),
      ], hasMore: false }),
    });
    await openWindow();
    expect(screen.getByRole('heading', { name: 'Aurora Sound' })).toBeInTheDocument();
    expect(screen.getByText('Studio Headphones')).toBeInTheDocument();
    expect(screen.getByText(/Usually replies within 2 hours/)).toBeInTheDocument();
    const log = screen.getByRole('log', { name: 'Messages' });
    // message 1 was read by the shop (theirLastReadMessageId = 1), message 3 not yet
    const first = within(log).getByText('Is it wireless?').closest('li')!;
    expect(within(first).getByText('Seen')).toBeInTheDocument();
    const third = within(log).getByText('Great, ordering now').closest('li')!;
    expect(within(third).getByText('Sent')).toBeInTheDocument();
    expect(within(log).getByText(/Shipped with DHL/)).toBeInTheDocument();
  });

  it('sends optimistically over the socket and replaces the pending bubble with the server message', async () => {
    api();
    await openWindow();
    const box = screen.getByLabelText('Message Aurora Sound');
    await userEvent.type(box, 'Do you ship to Canada?{Enter}');
    expect(screen.getByText('Do you ship to Canada?')).toBeInTheDocument();
    expect(screen.getByText('Sending')).toBeInTheDocument();
    const frame = transport.published.find((p) => p.destination === '/app/chat.send')!;
    expect(frame.body).toMatchObject({ conversationId: 'c1', type: 'TEXT', body: 'Do you ship to Canada?' });
    act(() => transport.emit({ queue: 'messages', payload: { kind: 'MESSAGE_CREATED', conversationId: 'c1',
      message: msg(5, 'BUYER', 'Do you ship to Canada?', { clientMessageId: String(frame.body.clientMessageId) }) } }));
    await waitFor(() => expect(screen.queryByText('Sending')).not.toBeInTheDocument());
    expect(screen.getAllByText('Do you ship to Canada?')).toHaveLength(1);
    // the shop reads it: the tick turns into "seen"
    act(() => transport.emit({ queue: 'receipts', payload: { conversationId: 'c1', side: 'VENDOR', lastReadMessageId: 5 } }));
    const bubble = screen.getByText('Do you ship to Canada?').closest('li')!;
    await waitFor(() => expect(within(bubble).getByText('Seen')).toBeInTheDocument());
  });

  it('sends an attached product card and the question as one message', async () => {
    api();
    renderApp(<ChatWindow conversationId="c1" attachment={{ type: 'PRODUCT_CARD', productId: 'p1', label: 'Studio Headphones' }} />,
      { path: '/chat', route: '/chat' });
    await screen.findByText('Yes, Bluetooth 5.3');
    expect(screen.getByText(/Attaching:/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Message Aurora Sound'), 'Does it come in white?{Enter}');
    const sends = transport.published.filter((p) => p.destination === '/app/chat.send');
    expect(sends).toHaveLength(1);
    expect(sends[0]!.body).toMatchObject({ type: 'PRODUCT_CARD', productId: 'p1', body: 'Does it come in white?' });
    expect(screen.queryByText(/Attaching:/)).not.toBeInTheDocument();
  });

  it('falls back to HTTP with the same client id when the socket is down', async () => {
    transport.connected = false;
    let posted: Record<string, unknown> | null = null;
    api({
      'POST /api/v1/chat/conversations/c1/messages': (init) => {
        posted = JSON.parse(String(init!.body));
        return json(201, msg(6, 'BUYER', 'Hello?', { clientMessageId: String(posted!.clientMessageId) }));
      },
      'POST /api/v1/chat/conversations/c1/read': () => json(200, { lastReadMessageId: 2 }),
    });
    await openWindow();
    await userEvent.type(screen.getByLabelText('Message Aurora Sound'), 'Hello?{Enter}');
    await waitFor(() => expect(posted).toMatchObject({ type: 'TEXT', body: 'Hello?' }));
    await waitFor(() => expect(screen.queryByText('Sending')).not.toBeInTheDocument());
    expect(screen.getByText('Hello?')).toBeInTheDocument();
  });

  it('shows why a message was refused and does not offer a pointless retry', async () => {
    api();
    await openWindow();
    await userEvent.type(screen.getByLabelText('Message Aurora Sound'), 'call me 415 555 0199{Enter}');
    const frame = transport.published.find((p) => p.destination === '/app/chat.send')!;
    act(() => transport.emit({ queue: 'errors', payload: { code: 'contact_info_not_allowed',
      message: 'For your safety, keep contact details and payments on MarketFlow.', conversationId: 'c1',
      clientMessageId: String(frame.body.clientMessageId) } }));
    expect(await screen.findByText(/keep contact details and payments on MarketFlow/)).toBeInTheDocument();
    expect(screen.getByText('Not sent')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.queryByText('call me 415 555 0199')).not.toBeInTheDocument();
  });

  it('shows typing, live presence and the reconnecting banner, and resyncs after reconnecting', async () => {
    let resynced = false;
    api({
      'GET /api/v1/chat/conversations/c1/messages': (_i, url) => {
        if (url.includes('after=')) {
          resynced = true;
          return json(200, { items: [msg(3, 'VENDOR', 'Sent while you were offline')], hasMore: false, changed: [] });
        }
        return json(200, { items: [msg(1, 'BUYER', 'Is it wireless?'), msg(2, 'VENDOR', 'Yes, Bluetooth 5.3')], hasMore: false });
      },
      'POST /api/v1/chat/conversations/c1/read': () => json(200, { lastReadMessageId: 3 }),
    });
    await openWindow();
    act(() => transport.emit({ queue: 'typing', payload: { conversationId: 'c1', side: 'VENDOR', name: 'Aurora Sound', typing: true } }));
    expect(await screen.findByText('Aurora Sound is typing…')).toBeInTheDocument();
    act(() => transport.emit({ queue: 'presence', payload: { userId: SELLER, online: true } }));
    expect(await screen.findByText('Online')).toBeInTheDocument();

    act(() => transport.setState('reconnecting'));
    expect(await screen.findByText(/Reconnecting…/)).toBeInTheDocument();
    act(() => transport.setState('connected', true));
    await waitFor(() => expect(screen.queryByText(/Reconnecting…/)).not.toBeInTheDocument());
    expect(await screen.findByText('Sent while you were offline')).toBeInTheDocument();
    expect(resynced).toBe(true);
  });

  it('marks what the buyer has seen as read', async () => {
    api();
    await openWindow();
    await new Promise((r) => setTimeout(r, 600));
    // message 2 is already read (myLastReadMessageId = 2): nothing to send
    expect(transport.published.filter((p) => p.destination === '/app/chat.read')).toHaveLength(0);
    act(() => transport.emit({ queue: 'messages', payload: { kind: 'MESSAGE_CREATED', conversationId: 'c1', message: msg(7, 'VENDOR', 'Anything else?') } }));
    await waitFor(() => expect(transport.published.find((p) => p.destination === '/app/chat.read')?.body)
      .toEqual({ conversationId: 'c1', lastReadMessageId: 7 }));
  });
});

describe('inbox', () => {
  const summary = (id: string, extra: Partial<ConversationSummary>): ConversationSummary => ({
    id, status: 'OPEN', counterpart: { kind: 'SHOP', name: 'Shop ' + id, userIds: ['u-' + id], online: false },
    context: { kind: 'GENERAL' }, unread: 0, muted: false, lastMessageAt: '2026-10-19T09:00:00Z',
    lastMessage: { id: 1, type: 'TEXT', text: 'hello', senderRole: 'VENDOR', deleted: false }, ...extra,
  });

  it('lists conversations with unread counts, own-message prefix, context and typing', async () => {
    mockApi({
      'POST /api/v1/auth/refresh': () => json(200, tokenFor()),
      'GET /api/v1/chat/unread-count': () => json(200, { asBuyer: 3, asVendor: 0 }),
      'GET /api/v1/chat/conversations': () => json(200, { items: [
        summary('a', { unread: 3, context: { kind: 'PRODUCT', productName: 'Cast-iron skillet' } }),
        summary('b', { lastMessage: { id: 2, type: 'TEXT', text: 'Thanks!', senderRole: 'BUYER', deleted: false } }),
      ], nextCursor: null }),
    });
    renderApp(<ConversationList side="BUYER" linkTo={(id) => `/account/messages/${id}`} emptyText="none" />, { path: '/m', route: '/m' });
    const a = (await screen.findByText('Shop a')).closest('a')!;
    expect(a).toHaveAttribute('href', '/account/messages/a');
    expect(within(a).getByText('3')).toBeInTheDocument();
    expect(within(a).getByText('Cast-iron skillet')).toBeInTheDocument();
    const b = screen.getByText('Shop b').closest('a')!;
    expect(within(b).getByText('You: Thanks!')).toBeInTheDocument();
    act(() => transport.emit({ queue: 'typing', payload: { conversationId: 'b', side: 'VENDOR', name: 'Shop b', typing: true } }));
    expect(await within(b).findByText('typing…')).toBeInTheDocument();
  });

  it('says so when there are no conversations', async () => {
    mockApi({
      'POST /api/v1/auth/refresh': () => json(200, tokenFor()),
      'GET /api/v1/chat/conversations': () => json(200, { items: [] }),
    });
    renderApp(<ConversationList side="BUYER" linkTo={(id) => id} emptyText="No conversations yet." />, { path: '/m', route: '/m' });
    expect(await screen.findByText('No conversations yet.', undefined, { timeout: 3000 })).toBeInTheDocument();
  });
});

describe('unread counts', () => {
  it('reading a conversation clears its row and the badge at once, without waiting for a refetch', () => {
    const qc = new QueryClient();
    const row = (id: string, unread: number) => ({ id, unread }) as unknown as ConversationSummary;
    qc.setQueryData(chatKeys.inbox('BUYER'), { pages: [{ items: [row('a', 2), row('b', 1)] }], pageParams: [null] });
    qc.setQueryData(chatKeys.unread, { asBuyer: 3, asVendor: 4 });
    clearUnread(qc, 'a', 'BUYER');
    const inbox = qc.getQueryData<{ pages: { items: ConversationSummary[] }[] }>(chatKeys.inbox('BUYER'))!;
    expect(inbox.pages[0]!.items.map((i) => i.unread)).toEqual([0, 1]);
    expect(qc.getQueryData(chatKeys.unread)).toEqual({ asBuyer: 1, asVendor: 4 });
    clearUnread(qc, 'a', 'BUYER'); // already read: nothing changes
    expect(qc.getQueryData(chatKeys.unread)).toEqual({ asBuyer: 1, asVendor: 4 });
  });
});

describe('reconnect backoff', () => {
  it('doubles up to 30 s with ±25 % jitter', () => {
    expect(backoffDelay(1, () => 0.5)).toBe(1000);
    expect(backoffDelay(2, () => 0.5)).toBe(2000);
    expect(backoffDelay(5, () => 0.5)).toBe(16000);
    expect(backoffDelay(9, () => 0.5)).toBe(30000);
    expect(backoffDelay(3, () => 0)).toBe(3000);
    expect(backoffDelay(3, () => 1)).toBe(5000);
  });
});
