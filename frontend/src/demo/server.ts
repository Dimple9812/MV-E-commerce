/**
 * In-browser stand-in for the MarketFlow backend, used only by the public demo build (VITE_DEMO_MODE=true).
 *
 * It intercepts fetch() calls to /api/* and answers them from a small in-memory database that is saved in this
 * visitor's localStorage. Business rules follow the real services where it matters for the UI (server-side pricing,
 * per-seller suborders, the checkout saga and its compensations, stock reservations, verified-purchase reviews,
 * seller earnings), but nothing here is the real system: there is no Kafka, no database and no other user.
 */
import type {
  Address, AdminOverview, AuditEntry, InventoryItem, OrderSummary, Page, Payment, Product, ProductCard, ProductSummary,
  Rating, Review, User, Vendor, VendorOverview, VendorSuborder,
} from '@/lib/types';
import {
  auditEntry, bookConfirmation, createOrderRecord, DB_VERSION, DEMO_PASSWORD, findVariant, moveStock, newId, notify,
  round2, sagaMove, seed, slugify, statusChange,
  type DemoDB, type DemoOrder, type DemoProduct, type DemoUser, type DemoVariant, type PricedLine,
} from './db';

const DB_KEY = 'marketflow.demo.db.v1';
const SESSION_KEY = 'marketflow.demo.session';

// ---------- persistence ----------

let db: DemoDB = load();

function load(): DemoDB {
  try {
    const raw = window.localStorage.getItem(DB_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as DemoDB;
      if (parsed.version === DB_VERSION) return parsed;
    }
  } catch { /* storage unavailable or corrupt: start fresh */ }
  return seed();
}

function save() {
  try {
    window.localStorage.setItem(DB_KEY, JSON.stringify(db));
  } catch {
    /* quota exceeded (large uploaded images) or storage blocked: keep working in memory for this tab */
  }
}

function getSession(): string | null {
  try { return window.localStorage.getItem(SESSION_KEY); } catch { return memorySession; }
}
let memorySession: string | null = null;
function setSessionUser(id: string | null) {
  memorySession = id;
  try {
    if (id) window.localStorage.setItem(SESSION_KEY, id); else window.localStorage.removeItem(SESSION_KEY);
  } catch { /* in-memory only */ }
}

/** Throws away every change this visitor made and starts again from the sample data. */
export function resetDemo() {
  db = seed();
  save();
  setSessionUser(null);
}

// ---------- HTTP plumbing ----------

class HttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string,
    readonly fieldErrors?: { field: string; message: string }[]) { super(message); }
}
const bad = (code: string, msg: string) => new HttpError(400, code, msg);
const notFound = (what = 'Resource') => new HttpError(404, 'not_found', `${what} not found`);
const conflict = (code: string, msg: string) => new HttpError(409, code, msg);
const forbidden = () => new HttpError(403, 'forbidden', 'You do not have permission to do that');

interface Req {
  method: string;
  path: string;
  query: URLSearchParams;
  body: unknown;
  form: FormData | null;
  headers: Headers;
  user: DemoUser | null;
  params: string[];
  now: number;
}
type Handler = (r: Req) => unknown | Promise<unknown>;
const routes: { method: string; re: RegExp; h: Handler }[] = [];
const route = (method: string, pattern: string, h: Handler) => {
  const re = new RegExp('^' + pattern.replace(/:[a-zA-Z]+/g, '([^/]+)') + '$');
  routes.push({ method, re, h });
};
const GET = (p: string, h: Handler) => route('GET', p, h);
const POST = (p: string, h: Handler) => route('POST', p, h);
const PUT = (p: string, h: Handler) => route('PUT', p, h);
const PATCH = (p: string, h: Handler) => route('PATCH', p, h);
const DEL = (p: string, h: Handler) => route('DELETE', p, h);

function json(status: number, body: unknown) {
  return new Response(body === null || body === undefined ? null : JSON.stringify(body), {
    status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', 'X-Correlation-Id': newId() },
  });
}

const latency = () => new Promise((r) => setTimeout(r, 90 + Math.random() * 160));

async function handle(method: string, url: URL, init: RequestInit | undefined): Promise<Response> {
  await latency();
  const now = Date.now();
  if (tick(now)) save();
  const headers = new Headers(init?.headers);
  let body: unknown = undefined;
  let form: FormData | null = null;
  if (init?.body instanceof FormData) form = init.body;
  else if (typeof init?.body === 'string' && init.body) { try { body = JSON.parse(init.body); } catch { body = init.body; } }
  const auth = headers.get('Authorization');
  const tokenUser = auth?.startsWith('Bearer demo.') ? db.users.find((u) => u.id === auth.slice('Bearer demo.'.length)) ?? null : null;
  const user = tokenUser && tokenUser.status === 'ACTIVE' ? tokenUser : null;
  for (const r of routes) {
    if (r.method !== method) continue;
    const m = url.pathname.match(r.re);
    if (!m) continue;
    try {
      const result = await r.h({ method, path: url.pathname, query: url.searchParams, body, form, headers, user, params: m.slice(1).map(decodeURIComponent), now });
      if (method !== 'GET') save();
      if (result instanceof Response) return result;
      const status = result === undefined ? 204 : method === 'POST' && /\/(register|checkout|applications|products|variants|images|upload|refunds|addresses|reviews|categories)$/.test(url.pathname) ? 201 : 200;
      return json(status, result);
    } catch (e) {
      if (e instanceof HttpError) {
        return json(e.status, { type: `https://marketflow.dev/problems/${e.code}`, title: e.message, status: e.status, detail: e.message, code: e.code, correlationId: newId(), errors: e.fieldErrors });
      }
      console.error('[demo] handler failed', e);
      return json(500, { status: 500, title: 'Internal error', detail: 'The demo backend hit an unexpected error.', code: 'internal_error' });
    }
  }
  return json(404, { status: 404, title: 'Not found', detail: `No demo handler for ${method} ${url.pathname}`, code: 'not_found' });
}

/** Routes /api/* fetches to the demo backend; everything else goes to the network as usual. */
export function installDemoBackend() {
  const realFetch = window.fetch.bind(window);
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const url = new URL(raw, window.location.origin);
    if (url.origin === window.location.origin && url.pathname.startsWith('/api/')) {
      const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
      return handle(method, url, init);
    }
    return realFetch(input, init);
  };
}

// ---------- helpers ----------

function requireUser(r: Req): DemoUser {
  if (!r.user) throw new HttpError(401, 'unauthorized', 'Sign in to continue');
  return r.user;
}
function requireRole(r: Req, role: 'VENDOR' | 'ADMIN'): DemoUser {
  const u = requireUser(r);
  if (!u.roles.includes(role)) throw forbidden();
  return u;
}
function myVendor(r: Req): Vendor {
  const u = requireRole(r, 'VENDOR');
  const v = db.vendors.find((x) => x.ownerUserId === u.id && x.status === 'APPROVED');
  if (!v) throw forbidden();
  return v;
}
const B = (r: Req) => (r.body ?? {}) as Record<string, unknown>;
const str = (v: unknown) => (typeof v === 'string' ? v.trim() : '');
const num = (v: unknown) => (typeof v === 'number' ? v : typeof v === 'string' && v !== '' ? Number(v) : NaN);
const int = (q: URLSearchParams, k: string, d: number) => { const n = Number(q.get(k)); return Number.isFinite(n) && q.get(k) !== null ? n : d; };

function paginate<T>(all: T[], q: URLSearchParams, defSize = 20): Page<T> {
  const size = Math.min(100, Math.max(1, int(q, 'size', defSize)));
  const page = Math.max(0, int(q, 'page', 0));
  return { items: all.slice(page * size, page * size + size), page, size, totalItems: all.length, totalPages: Math.max(1, Math.ceil(all.length / size)) };
}

const publicUser = (u: DemoUser): User => ({ id: u.id, email: u.email, fullName: u.fullName, roles: [...u.roles], status: u.status, createdAt: u.createdAt });
const token = (u: DemoUser) => ({ accessToken: `demo.${u.id}`, tokenType: 'Bearer', expiresIn: 900, user: publicUser(u) });
const iso = (ms: number) => new Date(ms).toISOString();
const byNewest = <T extends { createdAt: string }>(a: T, b: T) => b.createdAt.localeCompare(a.createdAt);
const audit = (r: Req, service: string, action: string, type: string, id: string, details = '') =>
  db.audit.unshift(auditEntry(service, r.user?.id ?? null, r.user?.roles.includes('ADMIN') ? 'ADMIN' : r.user?.roles.includes('VENDOR') ? 'VENDOR' : 'CUSTOMER', action, type, id, details, r.now));

function ratingOf(productId: string): Rating {
  const rs = db.reviews.filter((x) => x.productId === productId && x.status === 'PUBLISHED');
  const histogram: Record<string, number> = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 };
  rs.forEach((x) => { histogram[String(x.rating)] = (histogram[String(x.rating)] ?? 0) + 1; });
  return { productId, reviewCount: rs.length, averageRating: rs.length ? round2(rs.reduce((s, x) => s + x.rating, 0) / rs.length) : 0, histogram };
}

function activeVariants(p: DemoProduct) { return p.variants.filter((v) => v.active); }
function priceBounds(p: DemoProduct) {
  const prices = activeVariants(p).map((v) => v.price);
  return prices.length ? { min: Math.min(...prices), max: Math.max(...prices) } : { min: null, max: null };
}
const inStock = (p: DemoProduct) => activeVariants(p).some((v) => v.available > 0);
const vendorOf = (p: DemoProduct) => db.vendors.find((v) => v.id === p.vendorId);
const vendorVisible = (p: DemoProduct) => vendorOf(p)?.status === 'APPROVED';

function productView(p: DemoProduct): Product {
  const v = vendorOf(p);
  const b = priceBounds(p);
  const rating = ratingOf(p.id);
  return {
    id: p.id, vendor: v ? { id: v.id, name: v.businessName, slug: v.slug } : null, categoryId: p.categoryId, name: p.name,
    slug: p.slug, description: p.description, brand: p.brand, attributes: p.attributes, currency: 'USD', status: p.status,
    moderationNote: p.moderationNote, minPrice: b.min, maxPrice: b.max, inStock: inStock(p), ratingAvg: rating.averageRating,
    ratingCount: rating.reviewCount, maxPerOrder: p.maxPerOrder, publishedAt: p.publishedAt, updatedAt: p.updatedAt,
    images: p.images,
    variants: p.variants.map((x) => ({ id: x.id, sku: x.sku, name: x.name, attributes: x.attributes, price: x.price, compareAtPrice: x.compareAtPrice, active: x.active, availableQty: x.available })),
  };
}
function cardView(p: DemoProduct): ProductCard {
  const b = priceBounds(p);
  const rating = ratingOf(p.id);
  return {
    id: p.id, name: p.name, slug: p.slug, vendorId: p.vendorId, vendorName: vendorOf(p)?.businessName ?? '', categoryId: p.categoryId,
    minPrice: b.min ?? 0, maxPrice: b.max ?? 0, currency: 'USD', ratingAvg: rating.averageRating, ratingCount: rating.reviewCount,
    inStock: inStock(p), imageUrl: p.images[0]?.url ?? null, publishedAt: p.publishedAt ?? p.updatedAt,
  };
}
function summaryView(p: DemoProduct): ProductSummary {
  const b = priceBounds(p);
  return { id: p.id, name: p.name, status: p.status, minPrice: b.min, maxPrice: b.max, inStock: inStock(p), variantCount: p.variants.length, updatedAt: p.updatedAt, imageUrl: p.images[0]?.url ?? null };
}
function inventoryView(p: DemoProduct, v: DemoVariant): InventoryItem {
  return { variantId: v.id, productId: p.id, sku: v.sku, productName: p.name, variantName: v.name, available: v.available, reserved: v.reserved, sold: v.sold, lowStockThreshold: v.threshold, lowStock: v.available <= v.threshold, updatedAt: v.updatedAt };
}
function omit<T extends object, K extends keyof T>(o: T, ...keys: K[]): Omit<T, K> {
  const copy = { ...o };
  keys.forEach((k) => { delete copy[k]; });
  return copy;
}
/** The order as the API returns it, without the demo's bookkeeping fields. */
function orderView(o: DemoOrder) {
  return omit(o as DemoOrder & { idemKey?: string }, 'customerId', 'paymentToken', 'commission', 'saga', 'sagaStart', 'cancelAt', 'idemKey');
}
function summaryOf(o: DemoOrder): OrderSummary {
  return { id: o.id, status: o.status, grandTotal: o.grandTotal, currency: o.currency, itemCount: o.suborders.reduce((n, s) => n + s.items.reduce((m, i) => m + i.quantity, 0), 0), vendorCount: o.suborders.length, createdAt: o.createdAt };
}
function categoryTree(id: string): Set<string> {
  const out = new Set([id]);
  let grew = true;
  while (grew) {
    grew = false;
    db.categories.forEach((c) => { if (c.parentId && out.has(c.parentId) && !out.has(c.id)) { out.add(c.id); grew = true; } });
  }
  return out;
}
function product(id: string) {
  const p = db.products.find((x) => x.id === id);
  if (!p) throw notFound('Product');
  return p;
}
function ownProduct(r: Req, id: string) {
  const v = myVendor(r);
  const p = product(id);
  if (p.vendorId !== v.id) throw notFound('Product');
  return p;
}
function validateVariant(b: Record<string, unknown>, p: DemoProduct | null, existingId?: string) {
  const sku = str(b.sku);
  const price = num(b.price);
  if (!existingId) {
    if (!/^[A-Za-z0-9\-_.]{2,64}$/.test(sku)) throw bad('validation_failed', 'SKU must be 2-64 letters, digits or - _ .');
    const taken = db.products.some((x) => x.variants.some((v) => v.sku.toLowerCase() === sku.toLowerCase() && v.id !== existingId));
    if (taken) throw conflict('sku_taken', `SKU ${sku} is already used`);
  }
  if (!(price >= 0.01)) throw bad('validation_failed', 'Price must be at least 0.01');
  const compare = b.compareAtPrice === null || b.compareAtPrice === undefined || b.compareAtPrice === '' ? null : num(b.compareAtPrice);
  if (compare !== null && !(compare > price)) throw bad('validation_failed', 'Compare-at price must be higher than the price');
  void p;
  return { sku, name: str(b.name) || 'Standard', attributes: (b.attributes as Record<string, string>) ?? {}, price: round2(price), compareAtPrice: compare === null ? null : round2(compare) };
}

// ---------- simulated asynchronous work (checkout saga, cancellations, seller approval) ----------

function authDelay(tok: string) { return tok === 'tok_async' ? 4500 : tok === 'tok_timeout' ? 6500 : 2000; }

function nextDue(o: DemoOrder): number | null {
  const s = o.sagaStart;
  if (s === undefined) return null;
  const auth = authDelay(o.paymentToken);
  const last = Date.parse(o.saga.updatedAt);
  switch (o.saga.state) {
    case 'CREATED': return s + 300;
    case 'INVENTORY_PENDING': return s + 1000;
    case 'INVENTORY_RESERVED': return s + 1100;
    case 'PAYMENT_PENDING': return o.paymentToken === 'tok_timeout' ? s + 3000 : s + auth;
    case 'RECONCILIATION_REQUIRED': return s + auth;
    case 'PAYMENT_AUTHORIZED': return s + auth + 1000;
    case 'INVENTORY_CONFIRM_PENDING': return s + auth + 1800;
    case 'COMPENSATING': return last + 700;
    default: return null;
  }
}

function lines(o: DemoOrder) {
  return o.suborders.flatMap((s) => s.items.map((i) => ({ i, fv: findVariant(db, i.variantId) })));
}

function cancelOrder(o: DemoOrder, reason: string, at: number) {
  statusChange(o, 'CANCELLED', at, reason);
  o.status = 'CANCELLED';
  o.cancelReason = reason;
  o.cancelledAt = iso(at);
  o.suborders.forEach((s) => { s.status = 'CANCELLED'; });
  notify(db, o.customerId, 'ORDER_CANCELLED', 'Your order was cancelled', 'You have not been charged, or your payment has been refunded in full.', `/account/orders/${o.id}`, at);
}

function stepSaga(o: DemoOrder, at: number) {
  const pay = db.payments.find((p) => p.orderId === o.id);
  switch (o.saga.state) {
    case 'CREATED':
      sagaMove(o, 'INVENTORY_PENDING', 'ReserveInventoryRequested', at);
      break;
    case 'INVENTORY_PENDING': {
      const ls = lines(o);
      const short = ls.find(({ i, fv }) => !fv || fv.v.available < i.quantity);
      if (short) {
        sagaMove(o, 'CANCELLED', 'ReservationFailed', at, `insufficient stock for ${short.i.sku}`);
        o.saga.compensationReason = 'OUT_OF_STOCK';
        cancelOrder(o, 'OUT_OF_STOCK', at);
        break;
      }
      ls.forEach(({ i, fv }) => moveStock(db, fv!.v, 'RESERVE', { a: -i.quantity, r: i.quantity }, 'ORDER', o.id, at));
      sagaMove(o, 'INVENTORY_RESERVED', 'InventoryReserved', at);
      break;
    }
    case 'INVENTORY_RESERVED':
      sagaMove(o, 'PAYMENT_PENDING', 'AuthorizePaymentRequested', at);
      db.payments.push({ id: newId(), orderId: o.id, amount: o.grandTotal, currency: 'USD', status: 'PENDING', failureCode: null, refundedAmount: 0, authorizedAt: null, capturedAt: null, createdAt: iso(at), refunds: [] });
      break;
    case 'PAYMENT_PENDING':
      if (o.paymentToken === 'tok_timeout') {
        o.saga.lastError = 'Payment provider did not answer within 5 s; outcome unknown';
        sagaMove(o, 'RECONCILIATION_REQUIRED', 'PaymentOutcomeUnknown', at, 'reconciling with the provider');
      } else if (o.paymentToken === 'tok_decline') {
        if (pay) { pay.status = 'DECLINED'; pay.failureCode = 'card_declined'; }
        o.saga.compensationReason = 'PAYMENT_DECLINED';
        sagaMove(o, 'COMPENSATING', 'PaymentFailed', at, 'card_declined');
      } else {
        if (pay) { pay.status = 'AUTHORIZED'; pay.authorizedAt = iso(at); }
        sagaMove(o, 'PAYMENT_AUTHORIZED', o.paymentToken === 'tok_async' ? 'PaymentAuthorizedWebhook' : 'PaymentAuthorized', at);
      }
      break;
    case 'RECONCILIATION_REQUIRED':
      if (pay) { pay.status = 'AUTHORIZED'; pay.authorizedAt = iso(at); }
      sagaMove(o, 'PAYMENT_AUTHORIZED', 'PaymentReconciled', at, 'provider confirmed the authorisation');
      break;
    case 'PAYMENT_AUTHORIZED':
      if (o.paymentToken === 'tok_capture_fail') {
        if (pay) { pay.status = 'CAPTURE_FAILED'; pay.failureCode = 'capture_failed'; }
        o.saga.compensationReason = 'CAPTURE_FAILED';
        sagaMove(o, 'COMPENSATING', 'PaymentCaptureFailed', at, 'void authorisation and release stock');
      } else {
        if (pay) { pay.status = 'CAPTURED'; pay.capturedAt = iso(at); }
        sagaMove(o, 'INVENTORY_CONFIRM_PENDING', 'PaymentCaptured', at);
      }
      break;
    case 'INVENTORY_CONFIRM_PENDING':
      lines(o).forEach(({ i, fv }) => fv && moveStock(db, fv.v, 'CONFIRM', { r: -i.quantity, s: 0 + i.quantity }, 'ORDER', o.id, at));
      sagaMove(o, 'CONFIRMED', 'InventoryReservationConfirmed', at);
      bookConfirmation(db, o, at);
      break;
    case 'COMPENSATING':
      lines(o).forEach(({ i, fv }) => fv && moveStock(db, fv.v, 'RELEASE', { a: i.quantity, r: -i.quantity }, 'ORDER', o.id, at));
      if (pay && pay.status === 'CAPTURE_FAILED') pay.status = 'VOIDED';
      sagaMove(o, 'CANCELLED', 'CompensationsCompleted', at);
      cancelOrder(o, o.saga.compensationReason ?? 'CANCELLED', at);
      break;
  }
}

function finishCancellation(o: DemoOrder, at: number) {
  const pay = db.payments.find((p) => p.orderId === o.id);
  if (pay) {
    const amount = round2(pay.amount - pay.refundedAmount);
    if (amount > 0) pay.refunds.push({ id: newId(), amount, status: 'SUCCEEDED', reason: 'Order cancelled by customer', createdAt: iso(at), completedAt: iso(at) });
    pay.refundedAmount = pay.amount;
    pay.status = 'REFUNDED';
  }
  lines(o).forEach(({ i, fv }) => fv && moveStock(db, fv.v, 'RETURN', { a: i.quantity, s: -i.quantity }, 'ORDER', o.id, at, 'Order cancelled'));
  o.suborders.forEach((s) => {
    const sale = db.earnings.find((e) => e.suborderId === s.id && e.type === 'SALE');
    if (sale) db.earnings.push({ ...sale, id: newId(), type: 'REFUND', grossAmount: -sale.grossAmount, commission: -sale.commission, netAmount: -sale.netAmount, occurredAt: iso(at) });
  });
  const reason = o.history.filter((h) => h.toStatus === 'CANCELLATION_REQUESTED').pop()?.reason ?? 'Cancelled by customer';
  cancelOrder(o, reason, at);
}

/** Applies every simulated step that is due. Returns true when something changed. */
function tick(now: number): boolean {
  let changed = false;
  for (const o of db.orders) {
    for (let guard = 0; guard < 12; guard++) {
      const due = nextDue(o);
      if (due === null || due > now) break;
      stepSaga(o, due);
      changed = true;
    }
    if (o.cancelAt && o.cancelAt <= now && o.status === 'CANCELLATION_REQUESTED') {
      finishCancellation(o, o.cancelAt);
      o.cancelAt = undefined;
      changed = true;
    }
  }
  for (const v of db.vendors) {
    if (v.approveAt && v.approveAt <= now && v.status === 'PENDING') {
      approveVendor(v, v.commissionRate, v.approveAt);
      v.approveAt = undefined;
      changed = true;
    }
  }
  return changed;
}

function approveVendor(v: Vendor, rate: number, at: number) {
  v.status = 'APPROVED';
  v.commissionRate = rate;
  v.decidedAt = iso(at);
  v.statusReason = undefined;
  const owner = db.users.find((u) => u.id === v.ownerUserId);
  if (owner && !owner.roles.includes('VENDOR')) owner.roles.push('VENDOR');
  notify(db, v.ownerUserId, 'VENDOR_APPROVED', 'Your shop is approved', `${v.businessName} can now list products. Open the seller hub to add your first one.`, '/vendor', at);
}

// ---------- auth & account ----------

POST('/api/v1/auth/login', (r) => {
  const b = B(r);
  const u = db.users.find((x) => x.email.toLowerCase() === str(b.email).toLowerCase());
  if (!u || u.password !== b.password) throw new HttpError(401, 'invalid_credentials', 'E-mail or password is incorrect');
  if (u.status !== 'ACTIVE') throw new HttpError(403, 'account_disabled', 'This account has been deactivated');
  setSessionUser(u.id);
  return token(u);
});

POST('/api/v1/auth/register', (r) => {
  const b = B(r);
  const email = str(b.email).toLowerCase();
  const password = typeof b.password === 'string' ? b.password : '';
  if (!/^\S+@\S+\.\S+$/.test(email)) throw bad('validation_failed', 'Enter a valid e-mail address');
  if (password.length < 10 || !/[A-Za-z]/.test(password) || !/\d/.test(password)) throw bad('weak_password', 'Use at least 10 characters with a letter and a digit');
  if (db.users.some((u) => u.email === email)) throw conflict('email_taken', 'An account with this e-mail already exists');
  const u: DemoUser = { id: newId(), email, fullName: str(b.fullName) || email.split('@')[0]!, roles: ['CUSTOMER'], status: 'ACTIVE', createdAt: iso(r.now), password };
  db.users.push(u);
  db.addresses[u.id] = [];
  notify(db, u.id, 'WELCOME', 'Welcome to MarketFlow', 'Thanks for joining. Use WELCOME10 at checkout for 10% off your first order.', '/search', r.now);
  setSessionUser(u.id);
  return token(u);
});

POST('/api/v1/auth/refresh', () => {
  const id = getSession();
  const u = id ? db.users.find((x) => x.id === id && x.status === 'ACTIVE') : null;
  if (!u) throw new HttpError(401, 'invalid_refresh_token', 'Sign in again');
  return token(u);
});

POST('/api/v1/auth/logout', () => { setSessionUser(null); return undefined; });

POST('/api/v1/auth/password-reset/request', () => ({ accepted: true }));
POST('/api/v1/auth/password-reset/confirm', () => { throw bad('invalid_token', 'In the demo, password reset links are not sent. Use one of the demo accounts instead.'); });

PATCH('/api/v1/users/me', (r) => {
  const u = requireUser(r);
  const name = str(B(r).fullName);
  if (name.length < 2) throw bad('validation_failed', 'Enter your name');
  u.fullName = name;
  return publicUser(u);
});

POST('/api/v1/users/me/password', (r) => {
  const u = requireUser(r);
  const b = B(r);
  if (b.currentPassword !== u.password) throw bad('invalid_password', 'Current password is incorrect');
  const next = typeof b.newPassword === 'string' ? b.newPassword : '';
  if (next.length < 10) throw bad('weak_password', 'Use at least 10 characters with a letter and a digit');
  u.password = next;
  return undefined;
});

GET('/api/v1/users/me/addresses', (r) => db.addresses[requireUser(r).id] ?? []);
POST('/api/v1/users/me/addresses', (r) => {
  const u = requireUser(r);
  const b = B(r);
  const list = (db.addresses[u.id] ??= []);
  if (!str(b.recipient) || !str(b.line1) || !str(b.city) || !str(b.postalCode)) throw bad('validation_failed', 'Recipient, street, city and postal code are required');
  const makeDefault = b.makeDefault === true || list.length === 0;
  if (makeDefault) list.forEach((a) => { a.isDefault = false; });
  const a: Address = {
    id: newId(), label: str(b.label) || 'Address', recipient: str(b.recipient), line1: str(b.line1), line2: str(b.line2) || undefined,
    city: str(b.city), state: str(b.state) || undefined, postalCode: str(b.postalCode), country: str(b.country).toUpperCase() || 'US',
    phone: str(b.phone) || undefined, isDefault: makeDefault,
  };
  list.push(a);
  return a;
});
DEL('/api/v1/users/me/addresses/:id', (r) => {
  const u = requireUser(r);
  const list = db.addresses[u.id] ?? [];
  const i = list.findIndex((a) => a.id === r.params[0]);
  if (i < 0) throw notFound('Address');
  const [removed] = list.splice(i, 1);
  if (removed?.isDefault && list[0]) list[0].isDefault = true;
  return undefined;
});

// ---------- notifications ----------

GET('/api/v1/notifications/unread-count', (r) => {
  const u = requireUser(r);
  return { unread: db.notifications.filter((n) => n.userId === u.id && !n.readAt).length };
});
GET('/api/v1/notifications', (r) => {
  const u = requireUser(r);
  const mine = db.notifications.filter((n) => n.userId === u.id).sort(byNewest).map(({ userId: _u, ...n }) => n);
  return paginate(mine, r.query);
});
POST('/api/v1/notifications/read-all', (r) => {
  const u = requireUser(r);
  db.notifications.forEach((n) => { if (n.userId === u.id && !n.readAt) n.readAt = iso(r.now); });
  return undefined;
});

// ---------- storefront ----------

GET('/api/v1/categories', () => [...db.categories].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name)));

GET('/api/v1/products', (r) => {
  const q = r.query;
  const text = (q.get('q') ?? '').trim().toLowerCase();
  const terms = text.split(/\s+/).filter(Boolean);
  const cat = q.get('categoryId') ?? (q.get('category') ? db.categories.find((c) => c.slug === q.get('category'))?.id : undefined);
  const cats = cat ? categoryTree(cat) : null;
  const min = num(q.get('minPrice'));
  const max = num(q.get('maxPrice'));
  const minRating = num(q.get('minRating'));
  let list = db.products.filter((p) => p.status === 'PUBLISHED' && vendorVisible(p) && activeVariants(p).length > 0);
  if (q.get('vendorId')) list = list.filter((p) => p.vendorId === q.get('vendorId'));
  if (cats) list = list.filter((p) => cats.has(p.categoryId));
  const score = (p: DemoProduct) => {
    const hay = [p.name, p.description, p.brand ?? '', vendorOf(p)?.businessName ?? ''].join(' ').toLowerCase();
    return terms.reduce((s, t) => s + (p.name.toLowerCase().includes(t) ? 3 : hay.includes(t) ? 1 : 0), 0);
  };
  if (terms.length) list = list.filter((p) => terms.every((t) => [p.name, p.description, p.brand ?? '', vendorOf(p)?.businessName ?? ''].join(' ').toLowerCase().includes(t)));
  let cards = list.map(cardView);
  if (Number.isFinite(min)) cards = cards.filter((c) => c.maxPrice >= min);
  if (Number.isFinite(max)) cards = cards.filter((c) => c.minPrice <= max);
  if (Number.isFinite(minRating)) cards = cards.filter((c) => c.ratingAvg >= minRating);
  if (q.get('inStock') === 'true') cards = cards.filter((c) => c.inStock);
  const sort = q.get('sort') ?? 'newest';
  const sorters: Record<string, (a: ProductCard, b: ProductCard) => number> = {
    newest: (a, b) => b.publishedAt.localeCompare(a.publishedAt),
    price_asc: (a, b) => a.minPrice - b.minPrice,
    price_desc: (a, b) => b.maxPrice - a.maxPrice,
    rating: (a, b) => b.ratingAvg - a.ratingAvg || b.ratingCount - a.ratingCount,
    relevance: (a, b) => score(product(b.id)) - score(product(a.id)) || b.publishedAt.localeCompare(a.publishedAt),
  };
  cards.sort(sorters[sort] ?? sorters.newest);
  return paginate(cards, q, 24);
});

GET('/api/v1/products/:id', (r) => {
  const p = product(r.params[0]!);
  if (p.status !== 'PUBLISHED' || !vendorVisible(p)) throw notFound('Product');
  return productView(p);
});

GET('/api/v1/vendors/(?!me$)([^/]+)', (r) => {
  const v = db.vendors.find((x) => (x.id === r.params[0] || x.slug === r.params[0]) && x.status === 'APPROVED');
  if (!v) throw notFound('Seller');
  return { id: v.id, businessName: v.businessName, slug: v.slug, description: v.description, country: v.country, memberSince: v.decidedAt ?? v.submittedAt };
});

GET('/api/v1/inventory/availability', (r) => r.query.getAll('variantIds').map((id) => ({ variantId: id, available: findVariant(db, id)?.v.available ?? 0 })));

// ---------- reviews ----------

const purchased = (userId: string, productId: string) => db.orders.some((o) => o.customerId === userId
  && ['CONFIRMED', 'PARTIALLY_SHIPPED', 'SHIPPED', 'DELIVERED'].includes(o.status)
  && o.suborders.some((s) => s.items.some((i) => i.productId === productId)));

GET('/api/v1/products/:id/reviews', (r): Review[] => db.reviews
  .filter((x) => x.productId === r.params[0] && x.status === 'PUBLISHED').sort(byNewest).slice(0, int(r.query, 'size', 20))
  .map(({ productId: _p, customerId: _c, status: _s, moderationNote: _m, ...rest }) => rest));
GET('/api/v1/products/:id/rating', (r) => ratingOf(r.params[0]!));
GET('/api/v1/products/:id/reviews/eligibility', (r) => {
  const u = requireUser(r);
  if (!purchased(u.id, r.params[0]!)) return { eligible: false, reason: 'NOT_PURCHASED' };
  if (db.reviews.some((x) => x.productId === r.params[0] && x.customerId === u.id && x.status !== 'DELETED')) return { eligible: false, reason: 'ALREADY_REVIEWED' };
  return { eligible: true };
});
POST('/api/v1/products/:id/reviews', (r) => {
  const u = requireUser(r);
  const productId = r.params[0]!;
  if (!purchased(u.id, productId)) throw new HttpError(403, 'not_purchased', 'Only customers who bought this product can review it');
  if (db.reviews.some((x) => x.productId === productId && x.customerId === u.id && x.status !== 'DELETED')) throw conflict('already_reviewed', 'You already reviewed this product');
  const b = B(r);
  const rating = num(b.rating);
  if (!(rating >= 1 && rating <= 5)) throw bad('validation_failed', 'Rating must be 1 to 5');
  const [first, last] = u.fullName.split(' ');
  const review = { id: newId(), productId, customerId: u.id, authorName: `${first}${last ? ` ${last[0]}.` : ''}`, rating, title: str(b.title), body: str(b.body), verifiedPurchase: true, createdAt: iso(r.now), updatedAt: iso(r.now), status: 'PUBLISHED' as const, moderationNote: null };
  db.reviews.push(review);
  return review;
});

// ---------- checkout & orders ----------

POST('/api/v1/orders/checkout', (r) => {
  const u = requireUser(r);
  const b = B(r);
  const key = r.headers.get('Idempotency-Key');
  if (key) {
    const existing = db.orders.find((o) => (o as DemoOrder & { idemKey?: string }).idemKey === `${u.id}:${key}`);
    if (existing) return orderView(existing);
  }
  const items = Array.isArray(b.items) ? (b.items as { variantId: string; quantity: number }[]) : [];
  if (!items.length) throw bad('empty_cart', 'Your cart is empty');
  const priced: PricedLine[] = items.map((it) => {
    const fv = findVariant(db, it.variantId);
    if (!fv || fv.p.status !== 'PUBLISHED' || !fv.v.active || !vendorVisible(fv.p)) throw conflict('item_unavailable', 'An item in your cart is no longer available');
    const qty = Math.floor(Number(it.quantity));
    if (!(qty >= 1) || qty > fv.p.maxPerOrder) throw bad('validation_failed', `You can buy at most ${fv.p.maxPerOrder} of ${fv.p.name}`);
    return { p: fv.p, v: fv.v, qty };
  });
  const coupon = str(b.couponCode) || null;
  if (coupon && !['WELCOME10', 'SPRING15'].includes(coupon.toUpperCase())) throw bad('invalid_coupon', `Coupon ${coupon} is not valid`);
  const addr = (b.shippingAddress ?? {}) as Record<string, string>;
  if (!str(addr.recipient) || !str(addr.line1) || !str(addr.city)) throw bad('validation_failed', 'A complete delivery address is required');
  const tokenName = str(b.paymentToken) || 'tok_visa';
  const o = createOrderRecord(db, u, priced, coupon, Object.fromEntries(Object.entries(addr).filter(([, v]) => v)), tokenName, r.now);
  o.sagaStart = r.now;
  (o as DemoOrder & { idemKey?: string }).idemKey = key ? `${u.id}:${key}` : undefined;
  db.orders.push(o);
  return orderView(o);
});

function myOrder(r: Req) {
  const u = requireUser(r);
  const o = db.orders.find((x) => x.id === r.params[0] && (x.customerId === u.id || u.roles.includes('ADMIN')));
  if (!o) throw notFound('Order');
  return o;
}

GET('/api/v1/orders', (r) => {
  const u = requireUser(r);
  return paginate(db.orders.filter((o) => o.customerId === u.id).sort(byNewest).map(summaryOf), r.query);
});
GET('/api/v1/orders/:id', (r) => orderView(myOrder(r)));
POST('/api/v1/orders/:id/cancel', (r) => {
  const o = myOrder(r);
  if (o.status !== 'CONFIRMED' || !o.suborders.every((s) => s.status === 'CONFIRMED')) throw conflict('not_cancellable', 'This order can no longer be cancelled');
  const reason = str(B(r).reason);
  if (reason.length < 3) throw bad('validation_failed', 'Give a short reason');
  statusChange(o, 'CANCELLATION_REQUESTED', r.now, reason);
  o.status = 'CANCELLATION_REQUESTED';
  o.cancelAt = r.now + 1800;
  return orderView(o);
});

GET('/api/v1/payments/by-order/:id', (r) => {
  myOrder(r);
  const p = db.payments.find((x) => x.orderId === r.params[0]);
  if (!p) throw notFound('Payment');
  return p;
});

// ---------- selling ----------

GET('/api/v1/vendors/me', (r) => {
  const u = requireUser(r);
  const v = db.vendors.find((x) => x.ownerUserId === u.id);
  if (!v) throw notFound('Seller profile');
  return omit(v, 'approveAt');
});
POST('/api/v1/vendors/applications', (r) => {
  const u = requireUser(r);
  if (db.vendors.some((x) => x.ownerUserId === u.id)) throw conflict('already_applied', 'You already have a seller profile');
  const b = B(r);
  const name = str(b.businessName);
  if (name.length < 2) throw bad('validation_failed', 'Enter your business name');
  if (db.vendors.some((x) => x.businessName.toLowerCase() === name.toLowerCase())) throw conflict('name_taken', 'A shop with this name already exists');
  const v: Vendor & { approveAt?: number } = {
    id: newId(), ownerUserId: u.id, businessName: name, slug: slugify(name), description: str(b.description) || undefined,
    contactEmail: str(b.contactEmail) || u.email, contactPhone: str(b.contactPhone) || undefined, country: str(b.country).toUpperCase() || 'US',
    status: 'PENDING', commissionRate: 0.1, submittedAt: iso(r.now),
    // In the demo nobody else is there to review it, so an "administrator" approves it a few seconds later.
    approveAt: r.now + 4000,
  };
  db.vendors.push(v);
  audit(r, 'vendor-service', 'VENDOR_APPLIED', 'vendor', v.id, name);
  return omit(v, 'approveAt');
});

GET('/api/v1/vendor/dashboard', (r) => {
  const v = myVendor(r);
  const es = db.earnings.filter((e) => e.vendorId === v.id);
  const sales = es.filter((e) => e.type === 'SALE');
  const refunds = es.filter((e) => e.type === 'REFUND');
  const gross = round2(es.reduce((s, e) => s + e.grossAmount, 0));
  const commission = round2(es.reduce((s, e) => s + e.commission, 0));
  const net = round2(es.reduce((s, e) => s + e.netAmount, 0));
  const paid = round2(db.payouts.filter((p) => p.vendorId === v.id).reduce((s, p) => s + p.amount, 0));
  return { vendor: v, earnings: { grossSales: gross, commission, netEarnings: net, paidOut: paid, balance: round2(net - paid), salesCount: sales.length, refundCount: refunds.length, commissionRate: v.commissionRate } };
});

GET('/api/v1/vendor/analytics/overview', (r): VendorOverview => {
  const v = myVendor(r);
  const days = Math.min(365, Math.max(1, int(r.query, 'days', 30)));
  const since = r.now - days * 86_400_000;
  const subs = db.orders.filter((o) => Date.parse(o.createdAt) >= since && o.confirmedAt).flatMap((o) => o.suborders.filter((s) => s.vendorId === v.id).map((s) => ({ o, s })));
  const live = subs.filter(({ s }) => s.status !== 'CANCELLED');
  const revenue = round2(live.reduce((t, { s }) => t + s.itemsSubtotal - s.discountAmount, 0));
  const commission = round2(live.reduce((t, { o, s }) => t + (s.itemsSubtotal - s.discountAmount) * (o.commission[s.id] ?? v.commissionRate), 0));
  const byDay = new Map<string, { orders: number; net: number }>();
  for (let d = days - 1; d >= 0; d--) byDay.set(iso(r.now - d * 86_400_000).slice(0, 10), { orders: 0, net: 0 });
  live.forEach(({ o, s }) => {
    const k = o.createdAt.slice(0, 10);
    const e = byDay.get(k);
    if (e) { e.orders += 1; e.net = round2(e.net + (s.itemsSubtotal - s.discountAmount) * (1 - (o.commission[s.id] ?? v.commissionRate))); }
  });
  const best = new Map<string, { product_id: string; product_name: string; units: number; revenue: number }>();
  live.forEach(({ s }) => s.items.forEach((i) => {
    const e = best.get(i.productId) ?? { product_id: i.productId, product_name: i.productName, units: 0, revenue: 0 };
    e.units += i.quantity; e.revenue = round2(e.revenue + i.lineTotal); best.set(i.productId, e);
  }));
  return {
    totals: { orders: live.length, revenue, commission, net: round2(revenue - commission), cancelled: subs.length - live.length },
    salesOverTime: [...byDay.entries()].map(([day, x]) => ({ day, ...x })),
    bestSellers: [...best.values()].sort((a, b) => b.units - a.units).slice(0, 5),
    inventoryAlerts: db.products.filter((p) => p.vendorId === v.id && p.status !== 'ARCHIVED').flatMap((p) => p.variants.filter((x) => x.active && x.available <= x.threshold).map((x) => ({ sku: x.sku, product_name: p.name, available: x.available, threshold: x.threshold }))),
    lastEventAt: db.orders.length ? [...db.orders].sort(byNewest)[0]!.createdAt : null,
  };
});

GET('/api/v1/vendor/products', (r) => {
  const v = myVendor(r);
  const status = r.query.get('status');
  return paginate(db.products.filter((p) => p.vendorId === v.id && (!status || p.status === status))
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map(summaryView), r.query);
});
POST('/api/v1/vendor/products', (r) => {
  const v = myVendor(r);
  const b = B(r);
  const name = str(b.name);
  if (name.length < 3) throw bad('validation_failed', 'Name must be at least 3 characters');
  if (!db.categories.some((c) => c.id === b.categoryId)) throw bad('validation_failed', 'Choose a category');
  const variantsIn = Array.isArray(b.variants) ? (b.variants as Record<string, unknown>[]) : [];
  if (!variantsIn.length) throw bad('validation_failed', 'Add at least one variant');
  const skus = new Set<string>();
  const variants: DemoVariant[] = variantsIn.map((x) => {
    const val = validateVariant(x, null);
    if (skus.has(val.sku.toLowerCase())) throw conflict('sku_taken', `SKU ${val.sku} appears twice`);
    skus.add(val.sku.toLowerCase());
    return { id: newId(), ...val, active: true, available: 0, reserved: 0, sold: 0, threshold: 5, updatedAt: iso(r.now) };
  });
  const p: DemoProduct = {
    id: newId(), vendorId: v.id, categoryId: String(b.categoryId), name, slug: slugify(name), description: str(b.description),
    brand: str(b.brand) || null, attributes: (b.attributes as Record<string, string>) ?? {}, status: 'DRAFT', everPublished: false,
    moderationNote: null, maxPerOrder: Math.min(100, Math.max(1, num(b.maxPerOrder) || 10)), publishedAt: null, updatedAt: iso(r.now),
    variants, images: [],
  };
  db.products.push(p);
  audit(r, 'catalog-service', 'PRODUCT_CREATED', 'product', p.id, name);
  return productView(p);
});
GET('/api/v1/vendor/products/:id', (r) => productView(ownProduct(r, r.params[0]!)));
PATCH('/api/v1/vendor/products/:id', (r) => {
  const p = ownProduct(r, r.params[0]!);
  const b = B(r);
  if (b.name !== undefined) { const n = str(b.name); if (n.length < 3) throw bad('validation_failed', 'Name must be at least 3 characters'); p.name = n; p.slug = slugify(n); }
  if (b.categoryId !== undefined) { if (!db.categories.some((c) => c.id === b.categoryId)) throw bad('validation_failed', 'Choose a category'); p.categoryId = String(b.categoryId); }
  if (b.description !== undefined) p.description = str(b.description);
  if (b.brand !== undefined) p.brand = str(b.brand) || null;
  if (b.maxPerOrder !== undefined) p.maxPerOrder = Math.min(100, Math.max(1, num(b.maxPerOrder) || 10));
  if (b.attributes !== undefined) p.attributes = b.attributes as Record<string, string>;
  p.updatedAt = iso(r.now);
  return productView(p);
});
POST('/api/v1/vendor/products/:id/(publish|unpublish|archive)', (r) => {
  const p = ownProduct(r, r.params[0]!);
  const action = r.params[1];
  if (p.status === 'BLOCKED') throw conflict('product_blocked', 'This product is blocked by moderation');
  if (action === 'publish') {
    if (!activeVariants(p).length) throw conflict('no_active_variant', 'Activate at least one variant before publishing');
    p.status = 'PUBLISHED'; p.everPublished = true; p.publishedAt ??= iso(r.now);
  } else if (action === 'unpublish') {
    p.status = 'DRAFT';
  } else if (action === 'archive') {
    p.status = 'ARCHIVED';
  } else throw notFound();
  p.updatedAt = iso(r.now);
  audit(r, 'catalog-service', `PRODUCT_${action.toUpperCase()}ED`.replace('EED', 'ED'), 'product', p.id, p.name);
  return productView(p);
});
DEL('/api/v1/vendor/products/:id', (r) => {
  const p = ownProduct(r, r.params[0]!);
  if (p.everPublished) {
    p.status = 'ARCHIVED'; p.updatedAt = iso(r.now);
    return new Response(null, { status: 202 });
  }
  db.products = db.products.filter((x) => x.id !== p.id);
  return undefined;
});
POST('/api/v1/vendor/products/:id/variants', (r) => {
  const p = ownProduct(r, r.params[0]!);
  const val = validateVariant(B(r), p);
  p.variants.push({ id: newId(), ...val, active: true, available: 0, reserved: 0, sold: 0, threshold: 5, updatedAt: iso(r.now) });
  p.updatedAt = iso(r.now);
  return productView(p);
});
PATCH('/api/v1/vendor/products/:id/variants/:variantId', (r) => {
  const p = ownProduct(r, r.params[0]!);
  const v = p.variants.find((x) => x.id === r.params[1]);
  if (!v) throw notFound('Variant');
  const b = B(r);
  const val = validateVariant({ ...b, sku: v.sku }, p, v.id);
  Object.assign(v, { name: val.name, attributes: val.attributes, price: val.price, compareAtPrice: val.compareAtPrice });
  if (typeof b.active === 'boolean') v.active = b.active;
  v.updatedAt = p.updatedAt = iso(r.now);
  return productView(p);
});
POST('/api/v1/vendor/products/:id/images', (r) => {
  const p = ownProduct(r, r.params[0]!);
  const url = str(B(r).url);
  if (!/^https:\/\/\S+$/.test(url)) throw bad('validation_failed', 'Image URL must start with https://');
  if (p.images.length >= 8) throw conflict('too_many_images', 'A product can have at most 8 images');
  p.images.push({ id: newId(), url, altText: str(B(r).altText) || p.name });
  p.updatedAt = iso(r.now);
  return productView(p);
});
POST('/api/v1/vendor/products/:id/images/upload', async (r) => {
  const p = ownProduct(r, r.params[0]!);
  const file = r.form?.get('file');
  if (!(file instanceof Blob)) throw bad('invalid_image', 'Choose an image file');
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) throw bad('invalid_image', 'Only PNG, JPEG and WebP images are accepted');
  if (file.size > 2 * 1024 * 1024) throw bad('invalid_image', 'Images can be at most 2 MB');
  if (p.images.length >= 8) throw conflict('too_many_images', 'A product can have at most 8 images');
  const url = await new Promise<string>((resolve, reject) => {
    const fr = new FileReader();
    fr.onload = () => resolve(String(fr.result));
    fr.onerror = () => reject(fr.error);
    fr.readAsDataURL(file);
  });
  p.images.push({ id: newId(), url, altText: r.query.get('altText') ?? p.name });
  p.updatedAt = iso(r.now);
  return productView(p);
});
DEL('/api/v1/vendor/products/:id/images/:imageId', (r) => {
  const p = ownProduct(r, r.params[0]!);
  p.images = p.images.filter((i) => i.id !== r.params[1]);
  p.updatedAt = iso(r.now);
  return productView(p);
});

function ownVariant(r: Req, variantId: string) {
  const vendor = myVendor(r);
  const fv = findVariant(db, variantId);
  if (!fv || fv.p.vendorId !== vendor.id) throw notFound('Variant');
  return fv;
}
GET('/api/v1/vendor/inventory', (r) => {
  const v = myVendor(r);
  let items = db.products.filter((p) => p.vendorId === v.id).flatMap((p) => p.variants.map((x) => inventoryView(p, x)));
  if (r.query.get('lowStockOnly') === 'true') items = items.filter((i) => i.lowStock);
  return paginate(items.sort((a, b) => a.productName.localeCompare(b.productName) || a.sku.localeCompare(b.sku)), r.query, 50);
});
POST('/api/v1/vendor/inventory/adjustments', (r) => {
  const b = B(r);
  const { p, v } = ownVariant(r, String(b.variantId));
  const delta = Math.trunc(num(b.delta));
  if (!delta) throw bad('validation_failed', 'Enter a non-zero change');
  if (v.available + delta < 0) throw conflict('insufficient_stock', `Only ${v.available} available; you cannot remove ${-delta}`);
  moveStock(db, v, delta > 0 ? 'RESTOCK' : 'ADJUSTMENT', { a: delta }, 'ADJUSTMENT', newId(), r.now, str(b.reason));
  return inventoryView(p, v);
});
PUT('/api/v1/vendor/inventory/:variantId/threshold', (r) => {
  const { p, v } = ownVariant(r, r.params[0]!);
  const t = Math.trunc(num(B(r).threshold));
  if (!(t >= 0)) throw bad('validation_failed', 'Threshold must be zero or more');
  v.threshold = t;
  return inventoryView(p, v);
});
GET('/api/v1/vendor/inventory/:variantId/movements', (r) => {
  const { v } = ownVariant(r, r.params[0]!);
  return db.movements.filter((m) => m.variantId === v.id).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 100)
    .map(({ variantId: _v, ...m }) => m);
});

function vendorSuborder(o: DemoOrder, s: DemoOrder['suborders'][number]): VendorSuborder {
  const rate = o.commission[s.id] ?? 0.1;
  const net = s.itemsSubtotal - s.discountAmount;
  const commission = round2(net * rate);
  return {
    id: s.id, orderId: o.id, status: s.status, orderStatus: o.status, shippingAddress: o.shippingAddress, itemsSubtotal: s.itemsSubtotal,
    discountAmount: s.discountAmount, shippingAmount: s.shippingAmount, total: s.total, commissionRate: rate, commissionAmount: commission,
    vendorNetAmount: round2(net + s.shippingAmount - commission), carrier: s.carrier, trackingNumber: s.trackingNumber, orderedAt: o.createdAt,
    shippedAt: s.shippedAt, deliveredAt: s.deliveredAt, items: s.items,
  };
}
function findSuborder(r: Req) {
  const v = myVendor(r);
  for (const o of db.orders) {
    const s = o.suborders.find((x) => x.id === r.params[0] && x.vendorId === v.id);
    if (s) return { o, s };
  }
  throw notFound('Order');
}
function rollUp(o: DemoOrder, at: number) {
  const live = o.suborders.filter((s) => s.status !== 'CANCELLED');
  const next = live.every((s) => s.status === 'DELIVERED') ? 'DELIVERED' : live.every((s) => s.status === 'SHIPPED' || s.status === 'DELIVERED') ? 'SHIPPED'
    : live.some((s) => s.status === 'SHIPPED' || s.status === 'DELIVERED') ? 'PARTIALLY_SHIPPED' : o.status;
  if (next !== o.status) { statusChange(o, next, at); o.status = next as DemoOrder['status']; }
}
GET('/api/v1/vendor/suborders', (r) => {
  const v = myVendor(r);
  const status = r.query.get('status');
  const list = db.orders.filter((o) => o.confirmedAt || o.status === 'CANCELLATION_REQUESTED').flatMap((o) => o.suborders
    .filter((s) => s.vendorId === v.id && (!status || s.status === status)).map((s) => vendorSuborder(o, s)))
    .sort((a, b) => b.orderedAt.localeCompare(a.orderedAt));
  return paginate(list, r.query);
});
POST('/api/v1/vendor/suborders/:id/ship', (r) => {
  const { o, s } = findSuborder(r);
  if (s.status !== 'CONFIRMED' || o.status === 'CANCELLATION_REQUESTED') throw conflict('not_shippable', 'Only confirmed orders can be shipped');
  const b = B(r);
  if (!str(b.carrier) || !str(b.trackingNumber)) throw bad('validation_failed', 'Carrier and tracking number are required');
  statusChange(o, 'SHIPPED', r.now, null, s.id);
  Object.assign(s, { status: 'SHIPPED', carrier: str(b.carrier), trackingNumber: str(b.trackingNumber), shippedAt: iso(r.now) });
  rollUp(o, r.now);
  notify(db, o.customerId, 'SUBORDER_SHIPPED', `${s.vendorName} shipped your items`, `Tracking ${s.trackingNumber} with ${s.carrier}.`, `/account/orders/${o.id}`, r.now);
  return vendorSuborder(o, s);
});
POST('/api/v1/vendor/suborders/:id/deliver', (r) => {
  const { o, s } = findSuborder(r);
  if (s.status !== 'SHIPPED') throw conflict('not_shipped', 'Mark it shipped first');
  statusChange(o, 'DELIVERED', r.now, null, s.id);
  s.status = 'DELIVERED';
  s.deliveredAt = iso(r.now);
  rollUp(o, r.now);
  notify(db, o.customerId, 'SUBORDER_DELIVERED', 'Delivered', `Your items from ${s.vendorName} were delivered. How was it? Leave a review.`, `/account/orders/${o.id}`, r.now);
  return vendorSuborder(o, s);
});
GET('/api/v1/vendor/earnings', (r) => {
  const v = myVendor(r);
  return paginate(db.earnings.filter((e) => e.vendorId === v.id).sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)).map(({ vendorId: _v, ...e }) => e), r.query);
});
GET('/api/v1/vendor/earnings/payouts', (r) => {
  const v = myVendor(r);
  return db.payouts.filter((p) => p.vendorId === v.id).sort(byNewest);
});
PATCH('/api/v1/vendor/profile', (r) => {
  const v = myVendor(r);
  const b = B(r);
  const name = str(b.businessName);
  if (name.length < 2) throw bad('validation_failed', 'Enter your business name');
  Object.assign(v, { businessName: name, description: str(b.description) || undefined, contactEmail: str(b.contactEmail) || v.contactEmail, contactPhone: str(b.contactPhone) || undefined, country: str(b.country).toUpperCase() || v.country });
  db.orders.forEach((o) => o.suborders.forEach((s) => { if (s.vendorId === v.id) s.vendorName = name; }));
  return v;
});

// ---------- administration ----------

GET('/api/v1/admin/vendors', (r) => {
  requireRole(r, 'ADMIN');
  const status = r.query.get('status');
  return paginate(db.vendors.filter((v) => !status || v.status === status).sort((a, b) => b.submittedAt.localeCompare(a.submittedAt)).map(({ approveAt: _a, ...v }) => v), r.query);
});
POST('/api/v1/admin/vendors/:id/(approve|reject|suspend|reinstate)', (r) => {
  requireRole(r, 'ADMIN');
  const v = db.vendors.find((x) => x.id === r.params[0]);
  if (!v) throw notFound('Seller');
  const b = B(r);
  const action = r.params[1];
  if (action === 'approve') {
    if (v.status !== 'PENDING') throw conflict('invalid_state', 'Only pending applications can be approved');
    const rate = num(b.commissionRate);
    approveVendor(v, Number.isFinite(rate) ? rate : v.commissionRate, r.now);
    v.approveAt = undefined;
  } else if (action === 'reject' || action === 'suspend') {
    const reason = str(b.reason);
    if (reason.length < 3) throw bad('validation_failed', 'Give a reason');
    if (action === 'reject' && v.status !== 'PENDING') throw conflict('invalid_state', 'Only pending applications can be rejected');
    if (action === 'suspend' && v.status !== 'APPROVED') throw conflict('invalid_state', 'Only approved sellers can be suspended');
    v.status = action === 'reject' ? 'REJECTED' : 'SUSPENDED';
    v.statusReason = reason;
    v.decidedAt = iso(r.now);
    v.approveAt = undefined;
  } else if (action === 'reinstate') {
    if (v.status !== 'SUSPENDED') throw conflict('invalid_state', 'Only suspended sellers can be reinstated');
    v.status = 'APPROVED';
    v.statusReason = undefined;
  } else throw notFound();
  audit(r, 'vendor-service', `VENDOR_${action === 'approve' ? 'APPROVED' : action === 'reject' ? 'REJECTED' : action === 'suspend' ? 'SUSPENDED' : 'REINSTATED'}`, 'vendor', v.id, str(b.reason));
  return omit(v, 'approveAt');
});
PUT('/api/v1/admin/vendors/:id/commission', (r) => {
  requireRole(r, 'ADMIN');
  const v = db.vendors.find((x) => x.id === r.params[0]);
  if (!v) throw notFound('Seller');
  const rate = num(B(r).commissionRate);
  if (!(rate >= 0 && rate <= 0.5)) throw bad('validation_failed', 'Commission must be between 0 and 50%');
  v.commissionRate = rate;
  audit(r, 'vendor-service', 'VENDOR_COMMISSION_CHANGED', 'vendor', v.id, `commission ${rate}`);
  return v;
});

GET('/api/v1/admin/users', (r) => {
  requireRole(r, 'ADMIN');
  const q = (r.query.get('q') ?? '').toLowerCase();
  return paginate(db.users.filter((u) => !q || u.email.includes(q) || u.fullName.toLowerCase().includes(q)).sort(byNewest).map(publicUser), r.query);
});
POST('/api/v1/admin/users/:id/(activate|deactivate)', (r) => {
  const me = requireRole(r, 'ADMIN');
  const u = db.users.find((x) => x.id === r.params[0]);
  if (!u) throw notFound('User');
  if (u.id === me.id) throw conflict('self_action', 'You cannot deactivate your own account');
  u.status = r.params[1] === 'deactivate' ? 'DEACTIVATED' : 'ACTIVE';
  audit(r, 'identity-service', r.params[1] === 'deactivate' ? 'USER_DEACTIVATED' : 'USER_ACTIVATED', 'user', u.id, u.email);
  return publicUser(u);
});

GET('/api/v1/admin/products', (r) => {
  requireRole(r, 'ADMIN');
  const status = r.query.get('status');
  return paginate(db.products.filter((p) => !status || p.status === status).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map(summaryView), r.query);
});
POST('/api/v1/admin/products/:id/(block|unblock)', (r) => {
  requireRole(r, 'ADMIN');
  const p = product(r.params[0]!);
  if (r.params[1] === 'block') {
    if (p.status === 'BLOCKED') throw conflict('invalid_state', 'Already blocked');
    p.statusBeforeBlock = p.status;
    p.status = 'BLOCKED';
    p.moderationNote = str(B(r).note) || 'Blocked by moderation';
  } else {
    if (p.status !== 'BLOCKED') throw conflict('invalid_state', 'This product is not blocked');
    p.status = p.statusBeforeBlock ?? 'DRAFT';
    p.moderationNote = null;
  }
  p.updatedAt = iso(r.now);
  audit(r, 'catalog-service', r.params[1] === 'block' ? 'PRODUCT_BLOCKED' : 'PRODUCT_UNBLOCKED', 'product', p.id, p.moderationNote ?? '');
  return productView(p);
});

function categoryBody(r: Req, id?: string) {
  const b = B(r);
  const name = str(b.name);
  const slug = str(b.slug) || slugify(name);
  if (name.length < 2) throw bad('validation_failed', 'Enter a name');
  if (!/^[a-z0-9-]{2,80}$/.test(slug)) throw bad('validation_failed', 'Slug: lowercase letters, digits and dashes');
  if (db.categories.some((c) => c.slug === slug && c.id !== id)) throw conflict('slug_taken', 'Another category uses this slug');
  const parentId = (b.parentId as string | null) || null;
  if (parentId && (!db.categories.some((c) => c.id === parentId) || parentId === id)) throw bad('validation_failed', 'Unknown parent category');
  return { name, slug, parentId, description: str(b.description) || undefined, sortOrder: Math.trunc(num(b.sortOrder)) || 0 };
}
POST('/api/v1/admin/categories', (r) => {
  requireRole(r, 'ADMIN');
  const c = { id: newId(), ...categoryBody(r) };
  db.categories.push(c);
  audit(r, 'catalog-service', 'CATEGORY_CREATED', 'category', c.id, c.name);
  return c;
});
PUT('/api/v1/admin/categories/:id', (r) => {
  requireRole(r, 'ADMIN');
  const c = db.categories.find((x) => x.id === r.params[0]);
  if (!c) throw notFound('Category');
  Object.assign(c, categoryBody(r, c.id));
  audit(r, 'catalog-service', 'CATEGORY_UPDATED', 'category', c.id, c.name);
  return c;
});
DEL('/api/v1/admin/categories/:id', (r) => {
  requireRole(r, 'ADMIN');
  const id = r.params[0]!;
  if (db.categories.some((c) => c.parentId === id)) throw conflict('category_in_use', 'Move or delete its subcategories first');
  if (db.products.some((p) => p.categoryId === id)) throw conflict('category_in_use', 'Products still use this category');
  db.categories = db.categories.filter((c) => c.id !== id);
  audit(r, 'catalog-service', 'CATEGORY_DELETED', 'category', id);
  return undefined;
});

GET('/api/v1/admin/reviews', (r) => {
  requireRole(r, 'ADMIN');
  const status = r.query.get('status');
  return db.reviews.filter((x) => !status || x.status === status).sort(byNewest).slice(0, int(r.query, 'limit', 100));
});
POST('/api/v1/admin/reviews/:id/(hide|restore)', (r) => {
  requireRole(r, 'ADMIN');
  const rev = db.reviews.find((x) => x.id === r.params[0]);
  if (!rev) throw notFound('Review');
  rev.status = r.params[1] === 'hide' ? 'HIDDEN' : 'PUBLISHED';
  rev.moderationNote = r.params[1] === 'hide' ? str(B(r).note) || 'Hidden by moderator' : null;
  rev.updatedAt = iso(r.now);
  audit(r, 'review-service', r.params[1] === 'hide' ? 'REVIEW_HIDDEN' : 'REVIEW_RESTORED', 'review', rev.id);
  return rev;
});

GET('/api/v1/admin/orders', (r) => {
  requireRole(r, 'ADMIN');
  const status = r.query.get('status');
  return paginate(db.orders.filter((o) => !status || o.status === status).sort(byNewest).map(summaryOf), r.query);
});
GET('/api/v1/admin/orders/:id', (r) => { requireRole(r, 'ADMIN'); return orderView(myOrder(r)); });
GET('/api/v1/admin/orders/:id/saga', (r) => { requireRole(r, 'ADMIN'); return myOrder(r).saga; });

GET('/api/v1/admin/payments/summary', (r) => {
  requireRole(r, 'ADMIN');
  const m = new Map<string, { status: string; count: number; amount: number; refunded: number }>();
  db.payments.forEach((p) => {
    const e = m.get(p.status) ?? { status: p.status, count: 0, amount: 0, refunded: 0 };
    e.count += 1; e.amount = round2(e.amount + p.amount); e.refunded = round2(e.refunded + p.refundedAmount);
    m.set(p.status, e);
  });
  return [...m.values()].sort((a, b) => b.count - a.count);
});
GET('/api/v1/admin/payments', (r) => {
  requireRole(r, 'ADMIN');
  const status = r.query.get('status');
  return paginate(db.payments.filter((p) => !status || p.status === status).sort(byNewest), r.query);
});
POST('/api/v1/payments/:id/refunds', (r) => {
  requireRole(r, 'ADMIN');
  const p: Payment | undefined = db.payments.find((x) => x.id === r.params[0]);
  if (!p) throw notFound('Payment');
  if (!['CAPTURED', 'PARTIALLY_REFUNDED'].includes(p.status)) throw conflict('not_refundable', 'Only captured payments can be refunded');
  const amount = round2(num(B(r).amount));
  const left = round2(p.amount - p.refundedAmount);
  if (!(amount > 0) || amount > left) throw bad('validation_failed', `Refund between 0.01 and ${left.toFixed(2)}`);
  p.refunds.push({ id: newId(), amount, status: 'SUCCEEDED', reason: str(B(r).reason) || 'Refund', createdAt: iso(r.now), completedAt: iso(r.now) });
  p.refundedAmount = round2(p.refundedAmount + amount);
  p.status = p.refundedAmount >= p.amount ? 'REFUNDED' : 'PARTIALLY_REFUNDED';
  audit(r, 'payment-service', 'REFUND_ISSUED', 'payment', p.id, `amount ${amount}`);
  const o = db.orders.find((x) => x.id === p.orderId);
  if (o) notify(db, o.customerId, 'REFUND_ISSUED', 'Refund on its way', `We refunded $${amount.toFixed(2)} to your card.`, `/account/orders/${o.id}`, r.now);
  return p;
});

GET('/api/v1/admin/analytics/overview', (r): AdminOverview => {
  requireRole(r, 'ADMIN');
  const days = Math.min(365, Math.max(1, int(r.query, 'days', 30)));
  const since = r.now - days * 86_400_000;
  const orders = db.orders.filter((o) => Date.parse(o.createdAt) >= since);
  const confirmed = orders.filter((o) => o.confirmedAt);
  const gmv = round2(confirmed.filter((o) => o.status !== 'CANCELLED').reduce((s, o) => s + o.grandTotal, 0));
  const byStatus = new Map<string, number>();
  orders.forEach((o) => byStatus.set(o.status, (byStatus.get(o.status) ?? 0) + 1));
  const byDay = new Map<string, { orders: number; gmv: number }>();
  for (let d = days - 1; d >= 0; d--) byDay.set(iso(r.now - d * 86_400_000).slice(0, 10), { orders: 0, gmv: 0 });
  orders.forEach((o) => { const e = byDay.get(o.createdAt.slice(0, 10)); if (e) { e.orders += 1; if (o.confirmedAt && o.status !== 'CANCELLED') e.gmv = round2(e.gmv + o.grandTotal); } });
  const vendors = new Map<string, { vendor_id: string; vendor_name: string; suborders: number; revenue: number; commission: number }>();
  const best = new Map<string, { product_id: string; product_name: string; units: number; revenue: number }>();
  confirmed.filter((o) => o.status !== 'CANCELLED').forEach((o) => o.suborders.forEach((s) => {
    const net = s.itemsSubtotal - s.discountAmount;
    const e = vendors.get(s.vendorId) ?? { vendor_id: s.vendorId, vendor_name: s.vendorName, suborders: 0, revenue: 0, commission: 0 };
    e.suborders += 1; e.revenue = round2(e.revenue + net); e.commission = round2(e.commission + net * (o.commission[s.id] ?? 0.1));
    vendors.set(s.vendorId, e);
    s.items.forEach((i) => {
      const b = best.get(i.productId) ?? { product_id: i.productId, product_name: i.productName, units: 0, revenue: 0 };
      b.units += i.quantity; b.revenue = round2(b.revenue + i.lineTotal); best.set(i.productId, b);
    });
  }));
  const pays = db.payments.filter((p) => Date.parse(p.createdAt) >= since);
  const pm = new Map<string, { status: string; count: number; amount: number }>();
  pays.forEach((p) => { const e = pm.get(p.status) ?? { status: p.status, count: 0, amount: 0 }; e.count += 1; e.amount = round2(e.amount + p.amount); pm.set(p.status, e); });
  const succeeded = pays.filter((p) => ['CAPTURED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'AUTHORIZED'].includes(p.status)).length;
  const failed = pays.filter((p) => ['DECLINED', 'CAPTURE_FAILED', 'VOIDED', 'FAILED'].includes(p.status)).length;
  return {
    totals: { orders: orders.length, confirmed: confirmed.length, cancelled: orders.filter((o) => o.status === 'CANCELLED').length, gmv, averageOrderValue: confirmed.length ? round2(gmv / confirmed.filter((o) => o.status !== 'CANCELLED').length || 0) : 0, refundTotal: round2(pays.reduce((s, p) => s + p.refundedAmount, 0)) },
    statusDistribution: [...byStatus.entries()].map(([status, count]) => ({ status, count })),
    ordersOverTime: [...byDay.entries()].map(([day, x]) => ({ day, ...x })),
    salesByVendor: [...vendors.values()].sort((a, b) => b.revenue - a.revenue),
    bestSellers: [...best.values()].sort((a, b) => b.units - a.units).slice(0, 5),
    payments: [...pm.values()],
    paymentRates: { succeeded, failed, successRate: succeeded + failed ? round2((succeeded / (succeeded + failed)) * 100) : null },
    inventoryAlerts: db.products.filter((p) => p.status === 'PUBLISHED').flatMap((p) => p.variants.filter((v) => v.active && v.available <= v.threshold)
      .map((v) => ({ variant_id: v.id, sku: v.sku, product_name: p.name, available: v.available, threshold: v.threshold, business_name: vendorOf(p)?.businessName }))),
    operations: {
      deadLetters24h: 0,
      stuckPendingOrders: db.orders.filter((o) => o.status === 'PENDING' && r.now - Date.parse(o.createdAt) > 120_000).length,
      unresolvedPayments: db.payments.filter((p) => p.status === 'PENDING' && r.now - Date.parse(p.createdAt) > 60_000).length,
      failedOrders: db.orders.filter((o) => o.status === 'FAILED').length,
    },
    eventuallyConsistent: false,
    lastEventAt: db.orders.length ? [...db.orders].sort(byNewest)[0]!.createdAt : null,
    generatedAt: iso(r.now),
  };
});

GET('/api/v1/admin/sagas/summary', (r) => {
  requireRole(r, 'ADMIN');
  const out: Record<string, number> = {};
  db.orders.forEach((o) => { out[o.saga.state] = (out[o.saga.state] ?? 0) + 1; });
  return out;
});
GET('/api/v1/admin/sagas', (r) => {
  requireRole(r, 'ADMIN');
  const state = r.query.get('state');
  return db.orders.filter((o) => !state || o.saga.state === state).map((o) => o.saga)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0, int(r.query, 'limit', 50));
});
GET('/api/v1/admin/analytics/dead-letters', (r) => { requireRole(r, 'ADMIN'); return []; });
GET('/api/v1/admin/notifications/failed', (r) => { requireRole(r, 'ADMIN'); return []; });
GET('/api/v1/admin/audit', (r) => {
  requireRole(r, 'ADMIN');
  const action = (r.query.get('action') ?? '').toUpperCase();
  const rid = r.query.get('resourceId') ?? '';
  const list: AuditEntry[] = db.audit.filter((a) => (!action || a.action.includes(action)) && (!rid || a.resource_id.startsWith(rid)));
  return paginate(list, r.query, 50);
});

export const DEMO_ACCOUNTS = [
  { role: 'Customer', email: 'ada@demo.marketflow.local' },
  { role: 'Seller', email: 'aurora@demo.marketflow.local' },
  { role: 'Admin', email: 'admin@demo.marketflow.local' },
] as const;
export { DEMO_PASSWORD };
