/**
 * Demo mode data model and seed. Only bundled when VITE_DEMO_MODE=true (see main.tsx); the real app never ships it.
 *
 * Everything here is invented sample data for a public demo: sellers, products, people and orders are fictional.
 */
import type {
  Address, AdminReview, AuditEntry, Category, EarningsEntry, InboxItem, Order, Payment, Payout, ProductImage,
  Role, SagaView, StockMovement, User, Vendor,
} from '@/lib/types';
import { emptyChat, seedChats, type DemoChat } from './chat';

export const DEMO_PASSWORD = 'DemoPass2026!';

export interface DemoUser extends User { password: string }

export interface DemoVariant {
  id: string;
  sku: string;
  name: string;
  attributes: Record<string, string>;
  price: number;
  compareAtPrice: number | null;
  active: boolean;
  available: number;
  reserved: number;
  sold: number;
  threshold: number;
  updatedAt: string;
}

export interface DemoProduct {
  id: string;
  vendorId: string;
  categoryId: string;
  name: string;
  slug: string;
  description: string;
  brand: string | null;
  attributes: Record<string, string>;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'BLOCKED';
  statusBeforeBlock?: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  everPublished: boolean;
  moderationNote: string | null;
  maxPerOrder: number;
  publishedAt: string | null;
  updatedAt: string;
  variants: DemoVariant[];
  images: ProductImage[];
}

export interface DemoOrder extends Order {
  customerId: string;
  paymentToken: string;
  /** commission rate per suborder, frozen at checkout */
  commission: Record<string, number>;
  saga: SagaView;
  /** ms epoch the simulated saga started; steps are applied lazily as time passes */
  sagaStart?: number;
  cancelAt?: number;
}

export interface DemoDB {
  version: number;
  createdAt: string;
  users: DemoUser[];
  addresses: Record<string, Address[]>;
  categories: Category[];
  vendors: (Vendor & { approveAt?: number })[];
  products: DemoProduct[];
  reviews: AdminReview[];
  orders: DemoOrder[];
  payments: Payment[];
  notifications: (InboxItem & { userId: string })[];
  movements: (StockMovement & { variantId: string })[];
  earnings: (EarningsEntry & { vendorId: string })[];
  payouts: Payout[];
  audit: AuditEntry[];
  chat: DemoChat;
  seq: number;
}

/** 2: buyer-seller chat (stored data of version 1 is replaced by fresh sample data once) */
export const DB_VERSION = 2;

// ---------- helpers ----------

/** Deterministic PRNG (mulberry32) so every visitor starts from the same sample data. */
export function rng(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

let fixedCounter = 0;
/** UUID-shaped id that is stable across seeds (version-4 layout so validators accept it). */
export function fixedId(group: number): string {
  fixedCounter += 1;
  const n = fixedCounter.toString(16).padStart(12, '0');
  return `d${group.toString(16).padStart(7, '0')}-0000-4000-8000-${n}`;
}

export function newId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16));
}

export const round2 = (n: number) => Math.round(n * 100) / 100;
export const slugify = (s: string) => s.toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const iso = (ms: number) => new Date(ms).toISOString();

// ---------- reference data ----------

const CATEGORIES: Category[] = [
  { id: '10000000-0000-4000-8000-000000000001', parentId: null, name: 'Electronics', slug: 'electronics', description: 'Gadgets, audio and accessories', sortOrder: 1 },
  { id: '10000000-0000-4000-8000-000000000002', parentId: null, name: 'Home & Kitchen', slug: 'home-kitchen', description: 'Cookware, decor and storage', sortOrder: 2 },
  { id: '10000000-0000-4000-8000-000000000003', parentId: null, name: 'Fashion', slug: 'fashion', description: 'Clothing, shoes and bags', sortOrder: 3 },
  { id: '10000000-0000-4000-8000-000000000004', parentId: null, name: 'Sports & Outdoors', slug: 'sports-outdoors', description: 'Gear for active life', sortOrder: 4 },
  { id: '10000000-0000-4000-8000-000000000005', parentId: null, name: 'Books & Stationery', slug: 'books-stationery', description: 'Reading and writing', sortOrder: 5 },
  { id: '10000000-0000-4000-8000-000000000006', parentId: null, name: 'Beauty & Care', slug: 'beauty-care', description: 'Skincare and grooming', sortOrder: 6 },
  { id: '10000000-0000-4000-8000-000000000011', parentId: '10000000-0000-4000-8000-000000000001', name: 'Audio', slug: 'audio', description: 'Headphones and speakers', sortOrder: 1 },
  { id: '10000000-0000-4000-8000-000000000012', parentId: '10000000-0000-4000-8000-000000000001', name: 'Accessories', slug: 'electronics-accessories', description: 'Chargers, cables, cases', sortOrder: 2 },
  { id: '10000000-0000-4000-8000-000000000021', parentId: '10000000-0000-4000-8000-000000000002', name: 'Cookware', slug: 'cookware', description: 'Pans, pots and knives', sortOrder: 1 },
  { id: '10000000-0000-4000-8000-000000000022', parentId: '10000000-0000-4000-8000-000000000002', name: 'Decor', slug: 'decor', description: 'Lighting and ornaments', sortOrder: 2 },
  { id: '10000000-0000-4000-8000-000000000031', parentId: '10000000-0000-4000-8000-000000000003', name: 'Apparel', slug: 'apparel', description: 'Tops, bottoms and outerwear', sortOrder: 1 },
  { id: '10000000-0000-4000-8000-000000000032', parentId: '10000000-0000-4000-8000-000000000003', name: 'Footwear', slug: 'footwear', description: 'Sneakers and boots', sortOrder: 2 },
];
/** Free-to-use photos from Unsplash (https://unsplash.com/license), served by the Unsplash CDN. */
const img = (id: string) => `https://images.unsplash.com/photo-${id}?auto=format&fit=crop&w=800&h=1000&q=70`;

const CAT = Object.fromEntries(CATEGORIES.map((c) => [c.slug, c.id])) as Record<string, string>;

type SeedProduct = {
  cat: string; name: string; brand: string; description: string; stock: number; image?: string;
  attributes?: Record<string, string>;
  variants: [sku: string, name: string, price: number, compareAt?: number][];
};

interface SeedVendor {
  email: string; fullName: string; business: string; country: string; description: string; phone: string;
  commission: number; products: SeedProduct[];
}

const VENDORS: SeedVendor[] = [
  {
    email: 'aurora@demo.marketflow.local', fullName: 'Aurora Sound', business: 'Aurora Sound Co.', country: 'US', phone: '+1 415 555 0101',
    description: 'Independent audio workshop building headphones and speakers that last.', commission: 0.1,
    products: [
      { cat: 'audio', name: 'Aurora Studio Headphones', image: img('1590845947676-fa2576f401b2'), brand: 'Aurora', stock: 25, description: 'Closed-back studio headphones with 40mm drivers and memory-foam cushions. Folds flat for travel and ships with a detachable 1.5 m cable.', attributes: { Driver: '40 mm dynamic', Weight: '260 g' }, variants: [['AUR-HP-BLK', 'Matte Black', 149, 179], ['AUR-HP-SND', 'Sand', 149, 179]] },
      { cat: 'audio', name: 'Aurora Mini Bluetooth Speaker', image: img('1549400854-b4300f444934'), brand: 'Aurora', stock: 40, description: 'Pocket speaker with 12 hours playtime and IPX6 splash resistance. Pair two for stereo.', attributes: { Battery: '12 h', Rating: 'IPX6' }, variants: [['AUR-SPK-TEAL', 'Teal', 59], ['AUR-SPK-CORAL', 'Coral', 59]] },
      { cat: 'electronics-accessories', name: 'Braided USB-C Cable 2m', image: img('1595756630452-736bc8ef3693'), brand: 'Aurora', stock: 120, description: 'Nylon braided 100W USB-C to USB-C cable, tested to 15,000 bends.', variants: [['AUR-CBL-2M', '2 metres', 14.5]] },
      { cat: 'audio', name: 'Aurora Limited Edition Turntable', image: img('1526394931762-90052e97b376'), brand: 'Aurora', stock: 1, description: 'Hand-assembled belt-drive turntable in solid walnut. Only one unit available.', attributes: { Drive: 'Belt', Speeds: '33/45 rpm' }, variants: [['AUR-TT-LTD', 'Walnut', 499]] },
    ],
  },
  {
    email: 'northwind@demo.marketflow.local', fullName: 'Northwind Outfitters', business: 'Northwind Outfitters', country: 'CA', phone: '+1 604 555 0144',
    description: 'Outdoor apparel and footwear designed in the Rockies.', commission: 0.12,
    products: [
      { cat: 'footwear', name: 'Ridge Trail Running Shoe', image: img('1539185441755-769473a23570'), brand: 'Northwind', stock: 30, description: 'Lightweight trail shoe with a grippy lugged outsole and a rock plate for technical terrain.', attributes: { Drop: '6 mm', Weight: '290 g' }, variants: [['NW-RTR-42', 'EU 42', 119], ['NW-RTR-43', 'EU 43', 119], ['NW-RTR-44', 'EU 44', 119]] },
      { cat: 'apparel', name: 'Merino Base Layer Tee', image: img('1588973750926-d62985352d03'), brand: 'Northwind', stock: 50, description: '100% merino wool, odour resistant, 180 g/m². Warm when it is cold, cool when it is not.', attributes: { Fabric: 'Merino 180 g/m²' }, variants: [['NW-MER-S', 'S', 64], ['NW-MER-M', 'M', 64], ['NW-MER-L', 'L', 64]] },
      { cat: 'sports-outdoors', name: 'Packable Rain Shell', image: img('1610729679339-e5159d3f36a0'), brand: 'Northwind', stock: 3, description: '2.5-layer waterproof shell that packs into its own chest pocket.', attributes: { Waterproof: '20,000 mm' }, variants: [['NW-SHELL-M', 'M', 139, 159]] },
    ],
  },
  {
    email: 'kitchenco@demo.marketflow.local', fullName: 'Kitchen & Co', business: 'Kitchen & Co', country: 'GB', phone: '+44 20 7946 0958',
    description: 'Cookware and homeware for people who love to cook.', commission: 0.1,
    products: [
      { cat: 'cookware', name: 'Carbon Steel Skillet 28cm', image: img('1612477491914-a2f46ef8586e'), brand: 'Kitchen & Co', stock: 18, description: 'Pre-seasoned carbon steel pan that gets better with use. Oven safe to 300 °C.', attributes: { Diameter: '28 cm', Material: 'Carbon steel' }, variants: [['KC-SKL-28', '28 cm', 54]] },
      { cat: 'cookware', name: 'Chef Knife 20cm', image: img('1688398537368-29840062c4f3'), brand: 'Kitchen & Co', stock: 22, description: 'Forged stainless steel chef knife with full tang and a 15° edge.', attributes: { Blade: '20 cm', Steel: 'X50CrMoV15' }, variants: [['KC-KNF-20', '20 cm', 89]] },
      { cat: 'decor', name: 'Stoneware Pour-Over Set', image: img('1603124712721-e5699777490c'), brand: 'Kitchen & Co', stock: 12, description: 'Hand-glazed dripper and carafe for slow mornings. Each piece varies slightly.', variants: [['KC-POUR-WHT', 'Chalk white', 42], ['KC-POUR-MOS', 'Moss', 42]] },
      { cat: 'books-stationery', name: 'The Weeknight Cookbook', image: img('1542010589005-d1eacc3918f2'), brand: 'Kitchen & Co Press', stock: 60, description: '120 recipes that take less than 30 minutes, tested in a home kitchen.', attributes: { Pages: '288', Format: 'Hardcover' }, variants: [['KC-BOOK-WN', 'Hardcover', 24.99]] },
    ],
  },
];

const CUSTOMERS: [email: string, name: string][] = [
  ['ada@demo.marketflow.local', 'Ada Moreno'],
  ['linus@demo.marketflow.local', 'Linus Okafor'],
];

const REVIEW_TEXTS: [rating: number, title: string, body: string][] = [
  [5, 'Exactly as described', 'Arrived quickly and well packed. Quality is better than I expected for the price.'],
  [4, 'Very good, one small niggle', 'Does the job really well. Packaging could use less plastic, otherwise great.'],
  [5, 'Would buy again', 'Second one I have bought, this time as a gift. Holds up after months of daily use.'],
  [3, 'Decent', 'Works fine, but delivery took a few days longer than estimated.'],
  [5, 'Lovely', 'Beautifully made. The seller answered my question within an hour.'],
];

// ---------- seed ----------

export function seed(now = Date.now()): DemoDB {
  fixedCounter = 0;
  const rand = rng(20261012);
  const day = 86_400_000;
  const db: DemoDB = {
    version: DB_VERSION, createdAt: iso(now), users: [], addresses: {}, categories: CATEGORIES.map((c) => ({ ...c })),
    vendors: [], products: [], reviews: [], orders: [], payments: [], notifications: [], movements: [], earnings: [],
    payouts: [], audit: [], chat: emptyChat(), seq: 1000,
  };
  const addUser = (email: string, fullName: string, roles: Role[], createdDaysAgo: number) => {
    const u: DemoUser = { id: fixedId(1), email, fullName, roles, status: 'ACTIVE', createdAt: iso(now - createdDaysAgo * day), password: DEMO_PASSWORD };
    db.users.push(u);
    return u;
  };

  const admin = addUser('admin@demo.marketflow.local', 'Demo Administrator', ['CUSTOMER', 'ADMIN'], 120);
  const customers = CUSTOMERS.map(([e, n], i) => addUser(e, n, ['CUSTOMER'], 60 - i * 10));
  customers.forEach((c, i) => {
    db.addresses[c.id] = [{
      id: fixedId(2), label: 'Home', recipient: c.fullName, line1: i === 0 ? '221 Market Street' : '14 Harbour Road',
      city: i === 0 ? 'San Francisco' : 'Seattle', state: i === 0 ? 'CA' : 'WA', postalCode: i === 0 ? '94105' : '98101',
      country: 'US', phone: i === 0 ? '+1 415 555 0199' : '+1 206 555 0123', isDefault: true,
    }];
  });

  VENDORS.forEach((sv, vi) => {
    const owner = addUser(sv.email, sv.fullName, ['CUSTOMER', 'VENDOR'], 90 - vi * 7);
    const vendor: Vendor = {
      id: fixedId(3), ownerUserId: owner.id, businessName: sv.business, slug: slugify(sv.business), description: sv.description,
      contactEmail: sv.email, contactPhone: sv.phone, country: sv.country, status: 'APPROVED', commissionRate: sv.commission,
      submittedAt: iso(now - (88 - vi * 7) * day), decidedAt: iso(now - (87 - vi * 7) * day),
    };
    db.vendors.push(vendor);
    db.audit.push(auditEntry('vendor-service', admin.id, 'ADMIN', 'VENDOR_APPROVED', 'vendor', vendor.id, `commission ${sv.commission}`, now - (87 - vi * 7) * day));
    sv.products.forEach((sp, pi) => {
      const published = now - (40 - vi * 6 - pi * 3) * day;
      const product: DemoProduct = {
        id: fixedId(4), vendorId: vendor.id, categoryId: CAT[sp.cat]!, name: sp.name, slug: slugify(sp.name),
        description: sp.description, brand: sp.brand, attributes: sp.attributes ?? {}, status: 'PUBLISHED', everPublished: true,
        moderationNote: null, maxPerOrder: 10, publishedAt: iso(published), updatedAt: iso(published),
        images: sp.image ? [{ id: fixedId(6), url: sp.image, altText: sp.name }] : [],
        variants: sp.variants.map(([sku, name, price, compareAt]) => ({
          id: fixedId(5), sku, name, attributes: {}, price, compareAtPrice: compareAt ?? null, active: true,
          available: sp.stock, reserved: 0, sold: 0, threshold: Math.max(1, Math.min(5, Math.floor(sp.stock / 5))),
          updatedAt: iso(published),
        })),
      };
      product.variants.forEach((v) => db.movements.push({
        id: db.seq++, variantId: v.id, type: 'RESTOCK', deltaAvailable: v.available, deltaReserved: 0, deltaSold: 0,
        referenceType: 'ADJUSTMENT', referenceId: newIdFixed(), reason: 'Initial stock', createdAt: iso(published - 3_600_000),
      }));
      db.products.push(product);
    });
  });

  // A seller application waiting for review, so the admin approval flow has something to act on.
  const applicant = addUser('mira@demo.marketflow.local', 'Mira Lindqvist', ['CUSTOMER'], 3);
  db.vendors.push({
    id: fixedId(3), ownerUserId: applicant.id, businessName: 'Fjord Candle Studio', slug: 'fjord-candle-studio',
    description: 'Hand-poured soy candles from a small studio in Bergen.', contactEmail: applicant.email,
    country: 'NO', status: 'PENDING', commissionRate: 0.1, submittedAt: iso(now - 2 * day),
  });

  // ---- order history over the last four weeks, so dashboards and earnings have real numbers ----
  const sellable = db.products.flatMap((p) => p.variants.filter((v) => v.available > 2).map((v) => ({ p, v })));
  for (let k = 0; k < 26; k++) {
    const placed = now - Math.floor((27 - k) * day * 0.98 + rand() * day * 0.6);
    const customer = customers[Math.floor(rand() * customers.length)]!;
    const nLines = 1 + Math.floor(rand() * 3);
    const picks = new Map<string, { p: DemoProduct; v: DemoVariant; qty: number }>();
    for (let j = 0; j < nLines; j++) {
      const s = sellable[Math.floor(rand() * sellable.length)]!;
      // keep a healthy amount on hand so the sample store does not open with sold-out items
      if (!picks.has(s.v.id) && s.v.available > s.v.threshold + 2) picks.set(s.v.id, { ...s, qty: 1 + (rand() < 0.2 ? 1 : 0) });
    }
    if (picks.size === 0) continue;
    const declined = k === 5 || k === 17;
    const ageDays = (now - placed) / day;
    const fate: 'DELIVERED' | 'SHIPPED' | 'CONFIRMED' | 'CANCELLED' =
      declined ? 'CANCELLED' : ageDays > 6 ? 'DELIVERED' : ageDays > 2.5 ? 'SHIPPED' : 'CONFIRMED';
    buildHistoricOrder(db, customer, [...picks.values()], placed, fate, k === 3 ? 'WELCOME10' : null);
  }

  // Verified-purchase reviews for some delivered items.
  const reviewed = new Set<string>();
  db.orders.filter((o) => o.status === 'DELIVERED').forEach((o, i) => {
    o.suborders.flatMap((s) => s.items).forEach((it) => {
      const key = `${o.customerId}:${it.productId}`;
      if (reviewed.has(key) || rand() < 0.35) return;
      reviewed.add(key);
      const [rating, title, body] = REVIEW_TEXTS[(i + reviewed.size) % REVIEW_TEXTS.length]!;
      const at = iso(Date.parse(o.createdAt) + 5 * day);
      const author = db.users.find((u) => u.id === o.customerId)!;
      db.reviews.push({
        id: fixedId(7), productId: it.productId, customerId: author.id, authorName: author.fullName.split(' ')[0] + ' ' + author.fullName.split(' ')[1]![0] + '.',
        rating, title, body, verifiedPurchase: true, createdAt: at, updatedAt: at, status: 'PUBLISHED', moderationNote: null,
      });
    });
  });

  // One payout per seller from the first week of sales.
  db.vendors.filter((v) => v.status === 'APPROVED').forEach((v) => {
    const earned = db.earnings.filter((e) => e.vendorId === v.id && Date.parse(e.occurredAt) < now - 14 * day)
      .reduce((s, e) => s + e.netAmount, 0);
    if (earned > 0) {
      db.payouts.push({ id: fixedId(8), vendorId: v.id, amount: round2(earned), currency: 'USD', reference: `PO-${v.slug.slice(0, 4).toUpperCase()}-0001`, status: 'PAID', createdAt: iso(now - 13 * day) });
    }
  });

  // A failed e-mail in the retry/DLT pipeline, so the operations page shows what that view is for.
  db.audit.push(auditEntry('catalog-service', admin.id, 'ADMIN', 'CATEGORY_UPDATED', 'category', CAT['decor']!, 'description changed', now - 9 * day));
  db.audit.sort((a, b) => b.occurred_at.localeCompare(a.occurred_at));
  seedChats(db, now);
  return db;
}

function newIdFixed() {
  return fixedId(9);
}

export function auditEntry(service: string, actorId: string | null, role: string | null, action: string,
  resourceType: string, resourceId: string, details: string, at: number): AuditEntry {
  return {
    event_id: newId(), service, actor_id: actorId, actor_role: role, action, resource_type: resourceType,
    resource_id: resourceId, outcome: 'SUCCESS', details, correlation_id: newId(), occurred_at: new Date(at).toISOString(),
  };
}

// ---------- pricing (mirrors order-service PricingCalculator) ----------

export const PRICING = { taxRate: 0.08, flatShipping: 4.99, freeShippingThreshold: 50, coupons: { WELCOME10: 0.1, SPRING15: 0.15 } as Record<string, number> };

/** Splits total (in cents) proportionally to weights with the largest-remainder method, so parts add up exactly. */
export function allocate(total: number, weights: number[]): number[] {
  const sum = weights.reduce((a, b) => a + b, 0);
  if (total === 0 || sum === 0) return weights.map(() => 0);
  const cents = Math.round(total * 100);
  const exact = weights.map((w) => (cents * w) / sum);
  const out = exact.map(Math.floor);
  let left = cents - out.reduce((a, b) => a + b, 0);
  const order = exact.map((e, i) => [e - Math.floor(e), i] as const).sort((a, b) => b[0] - a[0] || a[1] - b[1]);
  for (const [, i] of order) { if (left <= 0) break; out[i]! += 1; left -= 1; }
  return out.map((c) => c / 100);
}

export interface PricedLine { p: DemoProduct; v: DemoVariant; qty: number }

export function priceOrder(db: DemoDB, lines: PricedLine[], couponCode: string | null) {
  const rate = couponCode ? PRICING.coupons[couponCode.trim().toUpperCase()] ?? 0 : 0;
  const byVendor = new Map<string, PricedLine[]>();
  [...lines].sort((a, b) => a.p.vendorId.localeCompare(b.p.vendorId) || a.v.sku.localeCompare(b.v.sku))
    .forEach((l) => byVendor.set(l.p.vendorId, [...(byVendor.get(l.p.vendorId) ?? []), l]));
  const groups = [...byVendor.entries()];
  const subtotals = groups.map(([, ls]) => round2(ls.reduce((s, l) => s + l.v.price * l.qty, 0)));
  const itemsSubtotal = round2(subtotals.reduce((a, b) => a + b, 0));
  const discountTotal = round2(itemsSubtotal * rate);
  const vendorDiscounts = allocate(discountTotal, subtotals);
  const suborders = groups.map(([vendorId, ls], i) => {
    const vendor = db.vendors.find((v) => v.id === vendorId)!;
    const lineSubs = ls.map((l) => round2(l.v.price * l.qty));
    const lineDisc = allocate(vendorDiscounts[i]!, lineSubs);
    const net = round2(subtotals[i]! - vendorDiscounts[i]!);
    const shipping = net >= PRICING.freeShippingThreshold ? 0 : PRICING.flatShipping;
    const tax = round2(net * PRICING.taxRate);
    const commission = round2(net * vendor.commissionRate);
    return {
      vendor, items: ls.map((l, j) => ({ l, lineSubtotal: lineSubs[j]!, discount: lineDisc[j]!, lineTotal: round2(lineSubs[j]! - lineDisc[j]!) })),
      itemsSubtotal: subtotals[i]!, discount: vendorDiscounts[i]!, shipping, tax, total: round2(net + shipping + tax), commission,
      vendorNet: round2(net + shipping - commission),
    };
  });
  return {
    suborders, itemsSubtotal, discountTotal,
    shippingTotal: round2(suborders.reduce((s, x) => s + x.shipping, 0)),
    taxTotal: round2(suborders.reduce((s, x) => s + x.tax, 0)),
    grandTotal: round2(suborders.reduce((s, x) => s + x.total, 0)),
    couponCode: rate > 0 ? couponCode!.trim().toUpperCase() : null,
  };
}

export function createOrderRecord(db: DemoDB, customer: User, lines: PricedLine[], coupon: string | null,
  address: Record<string, string>, token: string, at: number): DemoOrder {
  const q = priceOrder(db, lines, coupon);
  const id = newId();
  const order: DemoOrder = {
    id, customerId: customer.id, paymentToken: token, status: 'PENDING', checkoutState: 'CREATED', currency: 'USD',
    itemsSubtotal: q.itemsSubtotal, discountTotal: q.discountTotal, shippingTotal: q.shippingTotal, taxTotal: q.taxTotal,
    grandTotal: q.grandTotal, couponCode: q.couponCode, shippingAddress: address, cancelReason: null,
    createdAt: iso(at), confirmedAt: null, cancelledAt: null, commission: {},
    suborders: q.suborders.map((s) => ({
      id: newId(), vendorId: s.vendor.id, vendorName: s.vendor.businessName, status: 'PENDING', itemsSubtotal: s.itemsSubtotal,
      discountAmount: s.discount, shippingAmount: s.shipping, taxAmount: s.tax, total: s.total, carrier: null,
      trackingNumber: null, shippedAt: null, deliveredAt: null,
      items: s.items.map((it) => ({
        id: newId(), productId: it.l.p.id, variantId: it.l.v.id, sku: it.l.v.sku, productName: it.l.p.name,
        variantName: it.l.v.name, imageUrl: it.l.p.images[0]?.url ?? null, quantity: it.l.qty, unitPrice: it.l.v.price,
        lineSubtotal: it.lineSubtotal, discountAmount: it.discount, lineTotal: it.lineTotal,
      })),
    })),
    history: [{ suborderId: null, fromStatus: null, toStatus: 'PENDING', reason: null, at: iso(at) }],
    saga: {
      orderId: id, state: 'CREATED', attempt: 1, deadlineAt: iso(at + 120_000), compensationReason: null, lastError: null,
      createdAt: iso(at), updatedAt: iso(at), completedAt: null,
      transitions: [{ from: null, to: 'CREATED', trigger: 'CheckoutRequested', eventId: newId(), detail: null, at: iso(at) }],
    },
  };
  q.suborders.forEach((s, i) => { order.commission[order.suborders[i]!.id] = s.vendor.commissionRate; });
  return order;
}

/** Records one saga transition. */
export function sagaMove(o: DemoOrder, to: string, trigger: string, at: number, detail: string | null = null) {
  o.saga.transitions.push({ from: o.saga.state, to, trigger, eventId: newId(), detail, at: iso(at) });
  o.saga.state = to;
  o.saga.updatedAt = iso(at);
  o.checkoutState = to;
  if (['CONFIRMED', 'CANCELLED', 'FAILED'].includes(to)) o.saga.completedAt = iso(at);
}

export function statusChange(o: DemoOrder, to: string, at: number, reason: string | null = null, suborderId: string | null = null) {
  const from = suborderId ? o.suborders.find((s) => s.id === suborderId)?.status ?? null : o.status;
  o.history.push({ suborderId, fromStatus: from, toStatus: to, reason, at: iso(at) });
}

/** Moves stock and books the ledger entry for one variant. */
export function moveStock(db: DemoDB, v: DemoVariant, type: string, d: { a?: number; r?: number; s?: number }, refType: string, refId: string, at: number, reason?: string) {
  v.available += d.a ?? 0;
  v.reserved += d.r ?? 0;
  v.sold += d.s ?? 0;
  v.updatedAt = iso(at);
  db.movements.push({ id: db.seq++, variantId: v.id, type, deltaAvailable: d.a ?? 0, deltaReserved: d.r ?? 0, deltaSold: d.s ?? 0, referenceType: refType, referenceId: refId, reason, createdAt: iso(at) });
}

export function findVariant(db: DemoDB, variantId: string) {
  for (const p of db.products) {
    const v = p.variants.find((x) => x.id === variantId);
    if (v) return { p, v };
  }
  return null;
}

export function notify(db: DemoDB, userId: string, template: string, subject: string, body: string, link: string | null, at: number) {
  db.notifications.push({ id: newId(), userId, template, subject, body, link, createdAt: iso(at), readAt: null });
}

/** Side effects of a confirmed order: stock sold, payment captured, seller earnings, notifications. */
export function bookConfirmation(db: DemoDB, o: DemoOrder, at: number) {
  statusChange(o, 'CONFIRMED', at);
  o.status = 'CONFIRMED';
  o.confirmedAt = iso(at);
  o.suborders.forEach((s) => {
    s.status = 'CONFIRMED';
    const rate = o.commission[s.id] ?? 0.1;
    const net = round2(s.itemsSubtotal - s.discountAmount);
    const commission = round2(net * rate);
    db.earnings.push({ id: newId(), vendorId: s.vendorId, orderId: o.id, suborderId: s.id, type: 'SALE', grossAmount: round2(net + s.shippingAmount), commission, netAmount: round2(net + s.shippingAmount - commission), currency: 'USD', occurredAt: iso(at) });
    const vendor = db.vendors.find((v) => v.id === s.vendorId);
    if (vendor) notify(db, vendor.ownerUserId, 'VENDOR_NEW_ORDER', 'New order to pack', `Order ${o.id.slice(0, 8)} has ${s.items.length} item(s) for you to ship.`, '/vendor/orders', at);
  });
  notify(db, o.customerId, 'ORDER_CONFIRMED', 'Your order is confirmed', `We received your payment of $${o.grandTotal.toFixed(2)}. Sellers are preparing your items.`, `/account/orders/${o.id}`, at);
}

function buildHistoricOrder(db: DemoDB, customer: DemoUser, picks: { p: DemoProduct; v: DemoVariant; qty: number }[], placed: number,
  fate: 'DELIVERED' | 'SHIPPED' | 'CONFIRMED' | 'CANCELLED', coupon: string | null) {
  const addr = db.addresses[customer.id]![0]!;
  const address = { recipient: addr.recipient, line1: addr.line1, city: addr.city, state: addr.state ?? '', postalCode: addr.postalCode, country: addr.country };
  const o = createOrderRecord(db, customer, picks, coupon, address, fate === 'CANCELLED' ? 'tok_decline' : 'tok_visa', placed);
  const t = (s: number) => placed + s * 1000;
  sagaMove(o, 'INVENTORY_PENDING', 'ReserveInventoryRequested', t(0.2));
  picks.forEach(({ v, qty }) => moveStock(db, v, 'RESERVE', { a: -qty, r: qty }, 'ORDER', o.id, t(0.6)));
  sagaMove(o, 'INVENTORY_RESERVED', 'InventoryReserved', t(0.6));
  sagaMove(o, 'PAYMENT_PENDING', 'AuthorizePaymentRequested', t(0.7));
  const payment: Payment = { id: newId(), orderId: o.id, amount: o.grandTotal, currency: 'USD', status: 'PENDING', failureCode: null, refundedAmount: 0, authorizedAt: null, capturedAt: null, createdAt: iso(t(0.7)), refunds: [] };
  db.payments.push(payment);
  if (fate === 'CANCELLED') {
    payment.status = 'DECLINED';
    payment.failureCode = 'card_declined';
    sagaMove(o, 'COMPENSATING', 'PaymentFailed', t(1.4), 'card_declined');
    picks.forEach(({ v, qty }) => moveStock(db, v, 'RELEASE', { a: qty, r: -qty }, 'ORDER', o.id, t(1.6)));
    sagaMove(o, 'CANCELLED', 'InventoryReleased', t(1.7));
    o.saga.compensationReason = 'PAYMENT_DECLINED';
    statusChange(o, 'CANCELLED', t(1.7), 'PAYMENT_DECLINED');
    o.status = 'CANCELLED';
    o.cancelReason = 'PAYMENT_DECLINED';
    o.cancelledAt = iso(t(1.7));
    o.suborders.forEach((s) => { s.status = 'CANCELLED'; });
    db.orders.push(o);
    return;
  }
  payment.status = 'CAPTURED';
  payment.authorizedAt = iso(t(1.3));
  payment.capturedAt = iso(t(1.9));
  sagaMove(o, 'PAYMENT_AUTHORIZED', 'PaymentAuthorized', t(1.3));
  sagaMove(o, 'INVENTORY_CONFIRM_PENDING', 'PaymentCaptured', t(1.9));
  picks.forEach(({ v, qty }) => moveStock(db, v, 'CONFIRM', { r: -qty, s: qty }, 'ORDER', o.id, t(2.3)));
  sagaMove(o, 'CONFIRMED', 'InventoryReservationConfirmed', t(2.3));
  bookConfirmation(db, o, t(2.3));
  if (fate === 'SHIPPED' || fate === 'DELIVERED') {
    o.suborders.forEach((s, i) => {
      const shipped = placed + (20 + i * 3) * 3_600_000;
      statusChange(o, 'SHIPPED', shipped, null, s.id);
      s.status = 'SHIPPED';
      s.carrier = ['UPS', 'DHL', 'USPS'][i % 3]!;
      s.trackingNumber = `1Z${o.id.replace(/-/g, '').slice(0, 10).toUpperCase()}`;
      s.shippedAt = iso(shipped);
      if (fate === 'DELIVERED') {
        const delivered = shipped + 3 * 86_400_000;
        statusChange(o, 'DELIVERED', delivered, null, s.id);
        s.status = 'DELIVERED';
        s.deliveredAt = iso(delivered);
      }
    });
    statusChange(o, fate, Date.parse(o.suborders[o.suborders.length - 1]![fate === 'DELIVERED' ? 'deliveredAt' : 'shippedAt']!));
    o.status = fate;
  }
  // historic notifications are noise in the inbox; keep only the last few days'
  db.notifications = db.notifications.filter((n) => Date.parse(n.createdAt) > Date.now() - 4 * 86_400_000);
  db.orders.push(o);
}
