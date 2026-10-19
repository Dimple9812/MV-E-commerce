#!/usr/bin/env node
// Chat end-to-end checks against a RUNNING stack, through the same doors the browser uses: REST via the gateway
// (MF_API) and STOMP over the WebSocket (MF_WS: the storefront's nginx in Compose, so the upgrade path through
// nginx -> gateway -> chat-service is exercised). Requires scripts/seed-demo.mjs.   Usage: node scripts/e2e-chat.mjs
//   MF_WS=ws://127.0.0.1:3000/ws/chat node scripts/e2e-chat.mjs
import { randomUUID } from 'node:crypto';
import { Session, until, log } from './lib/api.mjs';

const PASSWORD = 'DemoPass2026!';
const WS = process.env.MF_WS ?? 'ws://127.0.0.1:8080/ws/chat';
const results = [];

async function check(name, fn) {
  const t0 = Date.now();
  try {
    await fn();
    results.push({ name, ok: true, ms: Date.now() - t0 });
    log(`PASS  ${name}`);
  } catch (e) {
    results.push({ name, ok: false, ms: Date.now() - t0, error: e.message });
    log(`FAIL  ${name}: ${e.message}`);
  }
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

// ---------- a minimal STOMP 1.2 client on Node's built-in WebSocket ----------

const frame = (command, headers, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`;

function parse(data) {
  return String(data).split('\0').filter((f) => f.trim()).map((f) => {
    const text = f.replace(/^\n+/, '');
    const split = text.indexOf('\n\n');
    const [command, ...lines] = text.slice(0, split).split('\n');
    const headers = Object.fromEntries(lines.map((l) => [l.slice(0, l.indexOf(':')), l.slice(l.indexOf(':') + 1)]));
    return { command, headers, body: text.slice(split + 2) };
  });
}

/** Connects with the access token in the CONNECT frame and subscribes to the user's queues. */
function stomp(token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS);
    const events = [];
    const waiters = [];
    const timer = setTimeout(() => reject(new Error(`no CONNECTED from ${WS} within 15 s`)), 15000);
    ws.onopen = () => ws.send(frame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '0,0', host: 'chat', Authorization: `Bearer ${token}` }));
    ws.onerror = (e) => reject(new Error(`WebSocket error: ${e.message ?? 'connection failed'}`));
    ws.onmessage = (e) => {
      for (const f of parse(e.data)) {
        if (f.command === 'CONNECTED') {
          clearTimeout(timer);
          ['messages', 'typing', 'receipts', 'errors'].forEach((q, i) =>
            ws.send(frame('SUBSCRIBE', { id: `s${i}`, destination: `/user/queue/${q}` })));
          // let the subscriptions register before anything is sent to them
          setTimeout(() => resolve(client), 300);
        } else if (f.command === 'MESSAGE') {
          const ev = { queue: f.headers.destination.split('/').pop(), payload: JSON.parse(f.body) };
          events.push(ev);
          waiters.slice().forEach((w) => { if (w.match(ev)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(ev); } });
        } else if (f.command === 'ERROR') {
          clearTimeout(timer);
          reject(new Error(`STOMP ERROR ${f.headers.message}`));
        }
      }
    };
    const client = {
      send: (destination, body) => ws.send(frame('SEND', { destination, 'content-type': 'application/json' }, JSON.stringify(body))),
      /** resolves with the first event (already received or future) that matches */
      next: (match, ms = 10000) => {
        const seen = events.find(match);
        if (seen) return Promise.resolve(seen);
        return new Promise((res, rej) => {
          const w = { match, resolve: (ev) => { clearTimeout(t); res(ev); } };
          const t = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); rej(new Error('expected event did not arrive within 10 s')); }, ms);
          waiters.push(w);
        });
      },
      close: () => ws.close(),
    };
  });
}

const address = { recipient: 'Ada Moreno', line1: '12 St James Square', city: 'London', postalCode: 'SW1Y 4LB', country: 'GB' };

async function main() {
  const ada = await new Session('ada').login('ada@demo.marketflow.local', PASSWORD);
  const linus = await new Session('linus').login('linus@demo.marketflow.local', PASSWORD);
  const aurora = await new Session('aurora').login('aurora@demo.marketflow.local', PASSWORD);

  const anon = new Session('anon');
  const card = (await anon.get('/api/v1/products?q=headphones&size=10')).data.items.find((p) => /Aurora Studio Headphones/.test(p.name));
  assert(card, 'Aurora Studio Headphones not in the storefront; run scripts/seed-demo.mjs');
  const product = (await anon.get(`/api/v1/products/${card.id}`)).data;
  const shopId = product.vendor.id;
  log(`shop ${product.vendor.name} (${shopId}), product ${product.name}`);

  let conversation;
  let adaWs;
  let auroraWs;

  await check('a buyer starts one conversation per shop and product; asking again returns the same one', async () => {
    conversation = (await ada.post('/api/v1/chat/conversations', { vendorId: shopId, productId: product.id })).data;
    const again = (await ada.post('/api/v1/chat/conversations', { vendorId: shopId, productId: product.id })).data;
    assert(conversation.id && again.id === conversation.id, `got ${conversation.id} and ${again.id}`);
    assert(conversation.side === 'BUYER', `side ${conversation.side}`);
  });

  await check('STOMP over the WebSocket: CONNECT with a JWT works, without one it is refused', async () => {
    adaWs = await stomp(ada.token);
    auroraWs = await stomp(aurora.token);
    let refused = false;
    try { (await stomp('not-a-token')).close(); } catch { refused = true; }
    assert(refused, 'CONNECT with an invalid token was accepted');
  });

  let sent;
  await check('a message sent over the socket reaches the shop live, and the buyer gets it back', async () => {
    const clientMessageId = randomUUID();
    adaWs.send('/app/chat.send', { conversationId: conversation.id, clientMessageId, type: 'PRODUCT_CARD', productId: product.id,
      body: 'Do these come in white?' });
    const atShop = await auroraWs.next((e) => e.queue === 'messages' && e.payload.message?.clientMessageId === clientMessageId);
    const back = await adaWs.next((e) => e.queue === 'messages' && e.payload.message?.clientMessageId === clientMessageId);
    sent = atShop.payload.message;
    assert(sent.senderRole === 'BUYER' && sent.type === 'PRODUCT_CARD', `got ${sent.senderRole} ${sent.type}`);
    assert(back.payload.message.id === sent.id, 'buyer echo has a different id');
  });

  await check('the shop sees it in its inbox with an unread count, then reading it sends a receipt to the buyer', async () => {
    const inbox = (await aurora.get('/api/v1/vendor/chat/conversations')).data.items;
    const row = inbox.find((c) => c.id === conversation.id);
    assert(row && row.unread >= 1, `inbox row ${JSON.stringify(row)}`);
    auroraWs.send('/app/chat.read', { conversationId: conversation.id, lastReadMessageId: sent.id });
    const receipt = await adaWs.next((e) => e.queue === 'receipts' && e.payload.conversationId === conversation.id
      && e.payload.side === 'VENDOR' && e.payload.lastReadMessageId >= sent.id);
    assert(receipt, 'no receipt');
  });

  await check('retrying with the same clientMessageId does not create a second message (HTTP)', async () => {
    const clientMessageId = randomUUID();
    const a = (await ada.post(`/api/v1/chat/conversations/${conversation.id}/messages`, { clientMessageId, type: 'TEXT', body: 'Retry me' })).data;
    const b = (await ada.post(`/api/v1/chat/conversations/${conversation.id}/messages`, { clientMessageId, type: 'TEXT', body: 'Retry me' },
      { expect: [200, 201] })).data;
    assert(a.id === b.id, `two messages: ${a.id}, ${b.id}`);
  });

  await check('phone numbers and payment links are refused with 422', async () => {
    for (const body of ['Call me on +1 415 555 0199', 'Pay me at paypal.me/somebody']) {
      const r = await ada.call('POST', `/api/v1/chat/conversations/${conversation.id}/messages`,
        { clientMessageId: randomUUID(), type: 'TEXT', body }, { expect: [422] });
      assert(r.data.code === 'contact_info_not_allowed', `code ${r.data.code}`);
    }
  });

  await check('someone else cannot read the conversation (404)', async () => {
    const r = await linus.call('GET', `/api/v1/chat/conversations/${conversation.id}/messages`, undefined, { expect: [404] });
    assert(r.status === 404, `status ${r.status}`);
  });

  await check('the shop owner gets an in-app notification for the new message (via Kafka)', async () => {
    await until('chat notification', async () => {
      const items = (await aurora.get('/api/v1/notifications?size=20')).data.items ?? [];
      return items.some((n) => /message/i.test(`${n.subject} ${n.template ?? ''}`) && Date.parse(n.createdAt) > Date.now() - 10 * 60_000);
    }, { timeoutMs: 30000 });
  });

  await check('shipping an order posts a SYSTEM message into its conversation (order.events -> chat)', async () => {
    const variant = product.variants.find((v) => v.active !== false) ?? product.variants[0];
    const order = (await ada.post('/api/v1/orders/checkout', { items: [{ variantId: variant.id, quantity: 1 }], shippingAddress: address,
      paymentToken: 'tok_visa' }, { headers: { 'Idempotency-Key': randomUUID() } })).data;
    const confirmed = await until(`order ${order.id} confirmed`, async () => {
      const o = (await ada.get(`/api/v1/orders/${order.id}`)).data;
      return o.status === 'CONFIRMED' ? o : null;
    }, { timeoutMs: 60000 });
    const sub = confirmed.suborders.find((s) => s.vendorId === shopId);
    assert(sub, 'no suborder for the shop');
    // chat-service learns about the suborder from OrderCreated/OrderConfirmed before the buyer can open it
    const orderChat = await until('order conversation can be opened', async () => {
      const r = await ada.call('POST', '/api/v1/chat/conversations', { vendorId: shopId, suborderId: sub.id }, { expect: [200, 201, 404] });
      return r.status === 404 ? null : r.data;
    }, { timeoutMs: 30000 });
    await aurora.post(`/api/v1/vendor/suborders/${sub.id}/ship`, { carrier: 'DHL', trackingNumber: 'JD0123456789' });
    const system = await adaWs.next((e) => e.queue === 'messages' && e.payload.conversationId === orderChat.id
      && e.payload.message?.type === 'SYSTEM', 30000);
    assert(/DHL|ship/i.test(system.payload.message.body ?? ''), `system message "${system.payload.message.body}"`);
  });

  adaWs?.close();
  auroraWs?.close();

  const failed = results.filter((r) => !r.ok);
  console.log('\n' + results.map((r) => `${r.ok ? 'PASS' : 'FAIL'}  ${String(r.ms).padStart(6)} ms  ${r.name}`
    + (r.ok ? '' : `\n        ${r.error}`)).join('\n'));
  console.log(`\n${results.length - failed.length}/${results.length} chat end-to-end checks passed`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('Chat E2E run aborted:', e.message);
  process.exit(2);
});
