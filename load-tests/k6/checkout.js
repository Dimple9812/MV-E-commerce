// Write path: authenticated checkout through the gateway, then polling until the saga finishes.
// Creates real orders. Run against a disposable stack only.
//   k6 run load-tests/k6/checkout.js -e RATE=2 -e DURATION=2m
// Each VU signs in once as one of the seeded customers; tok_visa is the always-approved sandbox card.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE || 'http://localhost:8080';
const PASSWORD = __ENV.CUSTOMER_PASSWORD || 'DemoPass2026!';   // seeded demo customers (development only)
const CUSTOMERS = (__ENV.CUSTOMERS || 'ada@demo.marketflow.local,linus@demo.marketflow.local').split(',');
const SKUS = (__ENV.SKUS || 'AUR-CBL-2M,KC-BOOK-WN,AUR-SPK-TEAL,AUR-SPK-CORAL').split(',');

const sagaDuration = new Trend('saga_completion_duration', true);
const confirmed = new Counter('orders_confirmed');
const cancelled = new Counter('orders_cancelled');
const unfinished = new Counter('orders_unfinished');

export const options = {
  scenarios: {
    checkout: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 2),            // checkouts started per second
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: Number(__ENV.VUS || 20),
      maxVUs: Number(__ENV.MAX_VUS || 60),
    },
  },
  thresholds: {
    'http_req_duration{endpoint:checkout}': ['p(95)<800'],
    'http_req_failed{endpoint:checkout}': ['rate<0.01'],
    saga_completion_duration: ['p(95)<5000'],
    orders_unfinished: ['count==0'],
  },
};

function login(email) {
  const r = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'k6' }, tags: { endpoint: 'login' } });
  if (r.status !== 200) throw new Error(`login ${email} failed: ${r.status}`);
  return r.json('accessToken');
}

export function setup() {
  const products = http.get(`${BASE}/api/v1/products?size=60`).json('items');
  const variants = [];
  for (const p of products) {
    const detail = http.get(`${BASE}/api/v1/products/${p.id}`).json();
    for (const v of detail.variants) if (SKUS.includes(v.sku)) variants.push({ variantId: v.id, sku: v.sku });
  }
  if (!variants.length) throw new Error('none of the SKUS are published: run scripts/seed-demo.mjs');
  const tokens = CUSTOMERS.map(login);
  return { variants, tokens };
}

const address = { recipient: 'Load Test', line1: '1 Benchmark Way', city: 'Portland', postalCode: '97201', country: 'US' };

export default function ({ variants, tokens }) {
  const token = tokens[exec.scenario.iterationInTest % tokens.length];
  const auth = { Authorization: `Bearer ${token}`, 'X-Requested-With': 'k6' };
  const v = variants[Math.floor(Math.random() * variants.length)];
  const started = Date.now();
  const res = http.post(`${BASE}/api/v1/orders/checkout`,
    JSON.stringify({ items: [{ variantId: v.variantId, quantity: 1 }], shippingAddress: address, paymentToken: 'tok_visa' }),
    { headers: { ...auth, 'Content-Type': 'application/json', 'Idempotency-Key': `k6-${exec.scenario.iterationInTest}-${started}` },
      tags: { endpoint: 'checkout' } });
  if (!check(res, { 'checkout accepted': (r) => r.status === 202 || r.status === 201 || r.status === 200 })) return;

  const id = res.json('id');
  for (let i = 0; i < 60; i++) {           // poll for up to ~15 s
    sleep(0.25);
    const o = http.get(`${BASE}/api/v1/orders/${id}`, { headers: auth, tags: { endpoint: 'order_poll' } });
    const status = o.status === 200 ? o.json('status') : null;
    if (status === 'CONFIRMED' || status === 'CANCELLED' || status === 'FAILED') {
      sagaDuration.add(Date.now() - started);
      (status === 'CONFIRMED' ? confirmed : cancelled).add(1);
      return;
    }
  }
  unfinished.add(1);
}
