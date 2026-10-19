// Chat load test: real STOMP-over-WebSocket clients against chat-service, as the browser speaks it.
//
// MODE=messaging (default): PAIRS virtual users, each driving one buyer and one shop of a prepared pair
//   (load-tests/prepare-chat.mjs). The buyer connects through the gateway (WS_BUYER), the seller straight to the
//   OTHER chat-service instance (WS_SELLER), so every message crosses PostgreSQL and the Redis fan-out between
//   instances. The buyer sends one message every INTERVAL_MS; the test measures
//     chat_delivery_ms  buyer SEND -> seller receives MESSAGE_CREATED   (end-to-end delivery, cross-instance)
//     chat_ack_ms       buyer SEND -> buyer receives its own MESSAGE_CREATED (the "sent" tick)
//   and counts messages sent, delivered, refused and lost (never delivered within the drain time).
//
// MODE=capacity: CONNS authenticated STOMP sessions held open on one instance (TARGET), CONNS_PER_VU per virtual
//   user, measuring how many connect and how long the STOMP CONNECT takes; heap usage is read from the
//   instance's actuator before and after.
//
//   k6 run -e PAIRS=100 -e DURATION=2m -e SUMMARY=load-tests/results/chat-100-pairs.json load-tests/k6/chat.js
//   k6 run -e MODE=capacity -e CONNS=2000 -e TARGET=ws://127.0.0.1:8091/ws/chat load-tests/k6/chat.js
//
// Use -e SUMMARY=<file> rather than --summary-export: k6's export includes the setup data, which holds the test
// users' access tokens; handleSummary below writes the metrics only.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { WebSocket } from 'k6/experimental/websockets';
import { setInterval, setTimeout, clearInterval } from 'k6/timers';
import { Counter, Rate, Trend } from 'k6/metrics';

const DATA = JSON.parse(open('../.chat-pairs.json'));
const MODE = __ENV.MODE || 'messaging';
const API = __ENV.API || 'http://127.0.0.1:8080';
const WS_BUYER = __ENV.WS_BUYER || 'ws://127.0.0.1:8080/ws/chat';
const WS_SELLER = __ENV.WS_SELLER || 'ws://127.0.0.1:8092/ws/chat';
const TARGET = __ENV.TARGET || 'ws://127.0.0.1:8091/ws/chat';
const PAIRS = Math.min(Number(__ENV.PAIRS || 50), DATA.pairs.length);
const DURATION_MS = parseDuration(__ENV.DURATION || '2m');
const INTERVAL_MS = Number(__ENV.INTERVAL_MS || 2000);
const DRAIN_MS = 5000;
const CONNS = Number(__ENV.CONNS || 1000);
const CONNS_PER_VU = Number(__ENV.CONNS_PER_VU || 100);
const HOLD_MS = parseDuration(__ENV.HOLD || '60s');

const delivery = new Trend('chat_delivery_ms', true);
const ack = new Trend('chat_ack_ms', true);
const connectTime = new Trend('stomp_connect_ms', true);
const sent = new Counter('chat_messages_sent');
const delivered = new Counter('chat_messages_delivered');
const refused = new Counter('chat_messages_refused');
const lost = new Counter('chat_messages_lost');
const connected = new Rate('stomp_connected');
const held = new Rate('stomp_held_to_end');

const capacityVus = Math.ceil(CONNS / CONNS_PER_VU);
export const options = {
  setupTimeout: '10m',
  scenarios: MODE === 'capacity'
    ? { capacity: { executor: 'per-vu-iterations', vus: capacityVus, iterations: 1, maxDuration: `${Math.ceil(HOLD_MS / 1000) + 300}s` } }
    : { messaging: { executor: 'per-vu-iterations', vus: PAIRS, iterations: 1, maxDuration: `${Math.ceil((DURATION_MS + DRAIN_MS) / 1000) + 120}s` } },
  thresholds: MODE === 'capacity'
    ? { stomp_connected: ['rate>0.99'] }
    : { chat_delivery_ms: ['p(95)<500'], chat_messages_lost: ['count==0'], stomp_connected: ['rate>0.99'] },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function parseDuration(s) {
  const m = /^(\d+)(ms|s|m)$/.exec(s);
  if (!m) throw new Error(`bad duration ${s}`);
  return Number(m[1]) * (m[2] === 'ms' ? 1 : m[2] === 's' ? 1000 : 60000);
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16));
}

// ---------- sign-in (fresh 15-minute access tokens for every run) ----------

export function setup() {
  const needed = MODE === 'capacity' ? Math.min(DATA.pairs.length, CONNS) : PAIRS;
  const emails = [];
  for (let i = 0; i < needed; i++) {
    emails.push(DATA.pairs[i].buyerEmail);
    if (MODE !== 'capacity') emails.push(DATA.pairs[i].sellerEmail);
  }
  const tokens = {};
  for (let i = 0; i < emails.length; i += 20) {
    const batch = emails.slice(i, i + 20).map((email) => ['POST', `${API}/api/v1/auth/login`,
      JSON.stringify({ email, password: DATA.password }), { headers: { 'Content-Type': 'application/json' } }]);
    http.batch(batch).forEach((r, j) => {
      if (r.status !== 200) throw new Error(`login ${emails[i + j]} -> ${r.status} ${r.body}`);
      tokens[emails[i + j]] = r.json('accessToken');
    });
  }
  return { tokens };
}

// ---------- minimal STOMP 1.2 over k6/websockets ----------

function frame(command, headers, body = '') {
  return `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`;
}

function parse(data) {
  return String(data).split('\0').filter((f) => f.trim().length > 0).map((f) => {
    const text = f.replace(/^\n+/, '');
    const split = text.indexOf('\n\n');
    const head = text.slice(0, split).split('\n');
    const headers = {};
    head.slice(1).forEach((h) => { const i = h.indexOf(':'); headers[h.slice(0, i)] = h.slice(i + 1); });
    return { command: head[0], headers, body: text.slice(split + 2) };
  });
}

/** Opens a STOMP session; onReady runs after CONNECTED and the subscriptions. */
function stomp(url, token, queues, { onReady, onMessage, onClose }) {
  const ws = new WebSocket(url);
  const started = Date.now();
  let ready = false;
  ws.onopen = () => ws.send(frame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '0,0', host: 'chat', Authorization: `Bearer ${token}` }));
  ws.onmessage = (e) => {
    for (const f of parse(e.data)) {
      if (f.command === 'CONNECTED') {
        ready = true;
        connectTime.add(Date.now() - started);
        connected.add(true);
        queues.forEach((q, i) => ws.send(frame('SUBSCRIBE', { id: `sub-${i}`, destination: `/user/queue/${q}` })));
        onReady && onReady(ws);
      } else if (f.command === 'MESSAGE') {
        onMessage && onMessage(f.headers.destination || '', JSON.parse(f.body));
      } else if (f.command === 'ERROR') {
        console.warn(`STOMP ERROR ${f.headers.message}`);
      }
    }
  };
  ws.onerror = (e) => { if (!ready) connected.add(false); console.warn(`ws error ${e.error}`); };
  ws.onclose = () => { onClose && onClose(ready); };
  return ws;
}

// ---------- messaging ----------

function messaging(data) {
  const pair = DATA.pairs[__VU - 1];
  const sentTime = {}; // clientMessageId -> sent at
  const pending = {}; // not yet delivered to the seller
  const unacked = {}; // not yet echoed back to the buyer ("sent" tick)
  let buyerWs = null;
  let sellerReady = false;
  let timer = null;
  let n = 0;

  const seller = stomp(WS_SELLER, data.tokens[pair.sellerEmail], ['messages'], {
    onReady: () => { sellerReady = true; },
    onMessage: (_dest, p) => {
      if (p.kind !== 'MESSAGE_CREATED' || p.message.senderRole !== 'BUYER') return;
      const id = p.message.clientMessageId;
      if (!pending[id]) return;
      delivery.add(Date.now() - sentTime[id]);
      delivered.add(1);
      delete pending[id];
    },
  });

  const start = () => {
    timer = setInterval(() => {
      if (!sellerReady) return;
      const clientMessageId = uuid();
      n += 1;
      sentTime[clientMessageId] = Date.now();
      pending[clientMessageId] = true;
      unacked[clientMessageId] = true;
      buyerWs.send(frame('SEND', { destination: '/app/chat.send', 'content-type': 'application/json' }, JSON.stringify({
        conversationId: pair.conversationId, clientMessageId, type: 'TEXT', body: `Load test message number ${'x'.repeat(n % 7)} hello`,
      })));
      sent.add(1);
    }, INTERVAL_MS);
  };
  const buyer = stomp(WS_BUYER, data.tokens[pair.buyerEmail], ['messages', 'errors'], {
    onReady: (ws) => { buyerWs = ws; start(); },
    onMessage: (dest, p) => {
      if (dest.endsWith('/errors')) {
        refused.add(1);
        delete pending[p.clientMessageId];
        delete unacked[p.clientMessageId];
        console.warn(`refused ${p.code}`);
        return;
      }
      const id = p.message && p.message.clientMessageId;
      if (p.kind === 'MESSAGE_CREATED' && p.message.senderRole === 'BUYER' && unacked[id]) {
        ack.add(Date.now() - sentTime[id]);
        delete unacked[id];
      }
    },
  });

  setTimeout(() => {
    if (timer) clearInterval(timer);
    setTimeout(() => {
      lost.add(Object.keys(pending).length);
      buyer.close();
      seller.close();
    }, DRAIN_MS);
  }, DURATION_MS);
}

// ---------- capacity ----------

function capacity(data) {
  const sockets = [];
  const first = (__VU - 1) * CONNS_PER_VU;
  const count = Math.min(CONNS_PER_VU, CONNS - first);
  const status = new Array(count).fill(false);
  let closing = false;
  for (let i = 0; i < count; i++) {
    // stagger connects (about 50 per second per VU) like users arriving, not a single thundering herd
    setTimeout(() => {
      const pair = DATA.pairs[(first + i) % DATA.pairs.length];
      sockets.push(stomp(TARGET, data.tokens[pair.buyerEmail], ['messages'], {
        onReady: () => { status[i] = true; },
        onClose: () => { if (!closing) status[i] = false; },
      }));
    }, i * 20);
  }
  setTimeout(() => {
    closing = true;
    status.forEach((ok) => held.add(ok));
    sockets.forEach((s) => s.close());
  }, count * 20 + HOLD_MS);
}

export default function (data) {
  if (MODE === 'capacity') capacity(data); else messaging(data);
}

export function teardown() {
  sleep(1);
  check(null, { 'test finished': () => true });
}

/** Prints the chat metrics and, with -e SUMMARY=<file>, saves the metrics (never the setup data with tokens). */
export function handleSummary(data) {
  const m = data.metrics;
  const fmt = (v) => (typeof v === 'number' ? (Number.isInteger(v) ? String(v) : v.toFixed(2)) : String(v));
  const lines = Object.keys(m).filter((k) => /^(chat_|stomp_|ws_sessions|ws_connecting)/.test(k)).sort().map((k) => {
    const v = m[k].values;
    const stats = Object.entries(v).map(([s, x]) => `${s}=${fmt(x)}`).join(' ');
    const failed = Object.values(m[k].thresholds || {}).some((t) => !t.ok);
    return `  ${failed ? '✗' : ' '} ${k.padEnd(26)} ${stats}`;
  });
  const out = { stdout: `\n${MODE} run\n${lines.join('\n')}\n` };
  if (__ENV.SUMMARY) out[__ENV.SUMMARY] = JSON.stringify({ metrics: m, options: { mode: MODE, pairs: PAIRS, duration: __ENV.DURATION || '2m', intervalMs: INTERVAL_MS, conns: CONNS } }, null, 2);
  return out;
}
