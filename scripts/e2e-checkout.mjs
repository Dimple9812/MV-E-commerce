#!/usr/bin/env node
// End-to-end verification against a RUNNING stack (all calls through the gateway, real Kafka in between).
// Requires the demo data from scripts/seed-demo.mjs.   Usage: node scripts/e2e-checkout.mjs
import { randomUUID } from 'node:crypto';
import { Session, until, log } from './lib/api.mjs';

const PASSWORD = 'DemoPass2026!';
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

const address = { recipient: 'Ada Moreno', line1: '12 St James Square', city: 'London', postalCode: 'SW1Y 4LB', country: 'GB' };

let catalog;   // product details, loaded once: the anonymous rate limit is 20 requests/s per IP
async function variantBySku(sku) {
  if (!catalog) {
    const anon = new Session('anon');
    const cards = (await anon.get('/api/v1/products?size=60')).data.items;
    catalog = [];
    for (const card of cards) catalog.push((await anon.get(`/api/v1/products/${card.id}`)).data);
  }
  for (const p of catalog) {
    const v = p.variants.find((x) => x.sku === sku);
    if (v) return { ...v, productId: p.id, vendorId: p.vendor.id };
  }
  throw new Error(`variant ${sku} not found in storefront`);
}

async function available(variantId) {
  const r = await new Session('anon').get(`/api/v1/inventory/availability?variantIds=${variantId}`);
  return r.data[0]?.available ?? 0;
}

async function checkout(customer, items, token, key = randomUUID()) {
  return customer.post('/api/v1/orders/checkout', { items, shippingAddress: address, paymentToken: token },
    { headers: { 'Idempotency-Key': key } });
}

async function finalState(customer, orderId, timeoutMs = 60000) {
  return until(`order ${orderId} to finish`, async () => {
    const o = (await customer.get(`/api/v1/orders/${orderId}`)).data;
    return ['CONFIRMED', 'CANCELLED', 'FAILED'].includes(o.status) ? o : null;
  }, { timeoutMs });
}

async function main() {
  const ada = await new Session('ada').login('ada@demo.marketflow.local', PASSWORD);
  const linus = await new Session('linus').login('linus@demo.marketflow.local', PASSWORD);
  const aurora = await new Session('aurora').login('aurora@demo.marketflow.local', PASSWORD);

  const headphones = await variantBySku('AUR-HP-BLK');
  const shoe = await variantBySku('NW-RTR-43');
  const skillet = await variantBySku('KC-SKL-28');
  const turntable = await variantBySku('AUR-TT-LTD');
  log('resolved variants from the public storefront');

  let confirmedOrderId;

  await check('multi-vendor checkout confirms with one suborder per vendor', async () => {
    const before = await Promise.all([headphones, shoe, skillet].map((v) => available(v.id)));
    const key = randomUUID();
    const items = [{ variantId: headphones.id, quantity: 1 }, { variantId: shoe.id, quantity: 2 }, { variantId: skillet.id, quantity: 1 }];
    const first = await checkout(ada, items, 'tok_visa', key);
    assert(first.status === 202, `expected 202, got ${first.status}`);
    const replay = await checkout(ada, items, 'tok_visa', key);
    assert(replay.data.id === first.data.id, 'same Idempotency-Key must return the same order');
    assert(replay.headers.get('idempotent-replayed') === 'true', 'replay header missing');

    const order = await finalState(ada, first.data.id);
    assert(order.status === 'CONFIRMED', `order ended ${order.status} (${order.cancelReason})`);
    assert(order.suborders.length === 3, `expected 3 suborders, got ${order.suborders.length}`);
    const sum = order.suborders.reduce((a, s) => a + Number(s.total), 0);
    assert(Math.abs(sum - Number(order.grandTotal)) < 0.001, 'suborder totals must add up to grand total');
    const after = await Promise.all([headphones, shoe, skillet].map((v) => available(v.id)));
    await until('availability projection updated', async () => {
      const now = await Promise.all([headphones, shoe, skillet].map((v) => available(v.id)));
      return now[0] === before[0] - 1 && now[1] === before[1] - 2 && now[2] === before[2] - 1;
    }, { timeoutMs: 15000 });
    confirmedOrderId = order.id;
    log(`  order ${order.id}: total ${order.grandTotal} ${order.currency}, stock ${before} -> ${after}`);
  });

  await check('vendor sees its own suborder and earnings (eventually consistent)', async () => {
    const subs = (await aurora.get('/api/v1/vendor/suborders?size=50')).data.items;
    const mine = subs.find((s) => s.orderId === confirmedOrderId);
    assert(mine, 'aurora should see its suborder');
    assert(mine.items.every((i) => i.sku.startsWith('AUR-')), 'vendor must only see its own items');
    await until('earnings ledger entry', async () =>
      (await aurora.get('/api/v1/vendor/earnings?size=100')).data.items.some((e) => e.orderId === confirmedOrderId));
  });

  await check('another customer cannot read the order (404)', async () => {
    const r = await linus.get(`/api/v1/orders/${confirmedOrderId}`, { expect: [404] });
    assert(r.status === 404, 'expected 404');
  });

  await check('declined payment cancels and releases the reservation', async () => {
    const before = await available(skillet.id);
    const r = await checkout(ada, [{ variantId: skillet.id, quantity: 1 }], 'tok_decline');
    const order = await finalState(ada, r.data.id);
    assert(order.status === 'CANCELLED' && order.cancelReason === 'PAYMENT_DECLINED',
      `expected CANCELLED/PAYMENT_DECLINED, got ${order.status}/${order.cancelReason}`);
    await until('stock restored', async () => (await available(skillet.id)) === before, { timeoutMs: 15000 });
  });

  await check('capture failure voids the authorisation and cancels', async () => {
    const r = await checkout(ada, [{ variantId: headphones.id, quantity: 1 }], 'tok_capture_fail');
    const order = await finalState(ada, r.data.id);
    assert(order.status === 'CANCELLED' && order.cancelReason === 'CAPTURE_FAILED', `got ${order.status}/${order.cancelReason}`);
    const payment = (await ada.get(`/api/v1/payments/by-order/${order.id}`)).data;
    assert(payment.status === 'VOIDED', `payment should be VOIDED, is ${payment.status}`);
  });

  await check('two customers racing for the last unit: exactly one wins', async () => {
    const stock = await available(turntable.id);
    if (stock !== 1) {
      await aurora.post('/api/v1/vendor/inventory/adjustments', { variantId: turntable.id, delta: 1 - stock, reason: 'e2e reset' });
      await until('turntable stock = 1', async () => (await available(turntable.id)) === 1, { timeoutMs: 15000 });
    }
    const [a, b] = await Promise.all([
      checkout(ada, [{ variantId: turntable.id, quantity: 1 }], 'tok_visa'),
      checkout(linus, [{ variantId: turntable.id, quantity: 1 }], 'tok_visa'),
    ]);
    const [oa, ob] = await Promise.all([finalState(ada, a.data.id), finalState(linus, b.data.id)]);
    const statuses = [oa.status, ob.status].sort();
    assert(statuses[0] === 'CANCELLED' && statuses[1] === 'CONFIRMED', `expected one CONFIRMED + one CANCELLED, got ${statuses}`);
    const loser = oa.status === 'CANCELLED' ? oa : ob;
    assert(loser.cancelReason === 'OUT_OF_STOCK', `loser reason ${loser.cancelReason}`);
    await until('turntable stock = 0', async () => (await available(turntable.id)) === 0, { timeoutMs: 15000 });
  });

  await check('lost payment response is reconciled, order still confirms', async () => {
    const r = await checkout(ada, [{ variantId: headphones.id, quantity: 1 }], 'tok_timeout');
    const order = await finalState(ada, r.data.id, 90000);
    assert(order.status === 'CONFIRMED', `expected CONFIRMED after reconciliation, got ${order.status}`);
  });

  await check('asynchronous authorisation completes through the signed webhook', async () => {
    const r = await checkout(ada, [{ variantId: skillet.id, quantity: 1 }], 'tok_async');
    const order = await finalState(ada, r.data.id, 60000);
    assert(order.status === 'CONFIRMED', `expected CONFIRMED, got ${order.status}`);
  });

  await check('customer cancellation after confirmation refunds and restocks', async () => {
    const before = await available(shoe.id);
    const r = await checkout(ada, [{ variantId: shoe.id, quantity: 1 }], 'tok_visa');
    const order = await finalState(ada, r.data.id);
    assert(order.status === 'CONFIRMED', `precondition: ${order.status}`);
    await ada.post(`/api/v1/orders/${order.id}/cancel`, { reason: 'Ordered the wrong size' });
    const cancelled = await until('cancellation to complete', async () => {
      const o = (await ada.get(`/api/v1/orders/${order.id}`)).data;
      return o.status === 'CANCELLED' ? o : null;
    }, { timeoutMs: 30000 });
    const payment = (await ada.get(`/api/v1/payments/by-order/${cancelled.id}`)).data;
    assert(payment.status === 'REFUNDED', `payment ${payment.status}`);
    await until('stock returned', async () => (await available(shoe.id)) === before, { timeoutMs: 15000 });
  });

  await check('vendor ships its suborder; parent becomes PARTIALLY_SHIPPED', async () => {
    const sub = (await aurora.get('/api/v1/vendor/suborders?status=CONFIRMED&size=50')).data.items
      .find((s) => s.orderId === confirmedOrderId);
    assert(sub, 'confirmed suborder for aurora not found');
    await aurora.post(`/api/v1/vendor/suborders/${sub.id}/ship`, { carrier: 'DHL', trackingNumber: 'JD0123456789' });
    const o = (await ada.get(`/api/v1/orders/${confirmedOrderId}`)).data;
    assert(o.status === 'PARTIALLY_SHIPPED', `order status ${o.status}`);
  });

  await check('only verified buyers can review; rating reaches the catalog', async () => {
    const sub = (await aurora.get('/api/v1/vendor/suborders?status=SHIPPED&size=50')).data.items
      .find((s) => s.orderId === confirmedOrderId);
    assert(sub, 'shipped suborder for aurora not found');
    await aurora.post(`/api/v1/vendor/suborders/${sub.id}/deliver`);
    const productId = headphones.productId;

    // a customer who never bought the product is refused by the server
    const stranger = new Session('stranger');
    await stranger.register(`stranger-${randomUUID().slice(0, 8)}@demo.marketflow.local`, PASSWORD, 'Sam Stranger');
    const denied = await stranger.post(`/api/v1/products/${productId}/reviews`,
      { rating: 1, title: 'Never bought it', body: 'Trying to review without a purchase.' }, { expect: [403, 409, 422] });
    assert(denied.status !== 201, 'review without purchase must be rejected');

    // the buyer becomes eligible once the delivery event reaches review-service
    const eligibility = await until('review eligibility after delivery', async () => {
      const e = (await ada.get(`/api/v1/products/${productId}/reviews/eligibility`)).data;
      return e.eligible || e.reason === 'ALREADY_REVIEWED' ? e : null;
    }, { timeoutMs: 15000 });
    if (eligibility.eligible) {
      const r = await ada.post(`/api/v1/products/${productId}/reviews`,
        { rating: 5, title: 'Great sound', body: 'Closed back, comfortable, no hiss at all.' }, { expect: [201, 200] });
      assert(r.data.verifiedPurchase === true, 'review should be marked as a verified purchase');
    }
    // ProductRatingUpdated -> catalog projection
    await until('rating visible on the product page', async () => {
      const p = (await new Session('anon').get(`/api/v1/products/${productId}`)).data;
      return p.ratingCount >= 1;
    }, { timeoutMs: 15000 });
  });

  await check('client-supplied prices are ignored', async () => {
    const r = await ada.post('/api/v1/orders/checkout', {
      items: [{ variantId: skillet.id, quantity: 1, unitPrice: 0.01, price: 0.01 }], shippingAddress: address,
      paymentToken: 'tok_decline', grandTotal: 0.01,
    }, { headers: { 'Idempotency-Key': randomUUID() } });
    assert(Number(r.data.itemsSubtotal) === Number(skillet.price), `subtotal ${r.data.itemsSubtotal} != ${skillet.price}`);
  });

  const failed = results.filter((r) => !r.ok);
  console.log('\n' + results.map((r) => `${r.ok ? 'PASS' : 'FAIL'}  ${String(r.ms).padStart(6)} ms  ${r.name}`
    + (r.ok ? '' : `\n        ${r.error}`)).join('\n'));
  console.log(`\n${results.length - failed.length}/${results.length} end-to-end checks passed`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('E2E run aborted:', e.message);
  process.exit(2);
});
