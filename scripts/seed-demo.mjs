#!/usr/bin/env node
// Seeds DEMO data through the public API (the same path a real user takes), so every service's projections are
// built by real Kafka events. Safe to run repeatedly: existing accounts/vendors are reused.
//
//   node scripts/seed-demo.mjs            (gateway at http://localhost:8080, admin from .env)
//
// Demo accounts (development only - never use these anywhere real):
//   admin     : BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD from the environment or .env (never hard-coded)
//   vendors   : aurora@demo.marketflow.local, northwind@demo.marketflow.local, kitchenco@demo.marketflow.local
//   customers : ada@demo.marketflow.local, linus@demo.marketflow.local
//   password for all demo users: DemoPass2026!
import { readFileSync, existsSync } from 'node:fs';
import { Session, until, log } from './lib/api.mjs';

function env(key, fallback) {
  if (process.env[key]) return process.env[key];
  for (const f of ['.env', '../.env']) {
    if (existsSync(f)) {
      const line = readFileSync(f, 'utf8').split(/\r?\n/).find((l) => l.startsWith(key + '='));
      if (line) return line.slice(key.length + 1).trim();
    }
  }
  return fallback;
}

const PASSWORD = 'DemoPass2026!';
const CAT = {
  audio: '10000000-0000-0000-0000-000000000011',
  accessories: '10000000-0000-0000-0000-000000000012',
  cookware: '10000000-0000-0000-0000-000000000021',
  decor: '10000000-0000-0000-0000-000000000022',
  apparel: '10000000-0000-0000-0000-000000000031',
  footwear: '10000000-0000-0000-0000-000000000032',
  sports: '10000000-0000-0000-0000-000000000004',
  books: '10000000-0000-0000-0000-000000000005',
};

const VENDORS = [
  {
    email: 'aurora@demo.marketflow.local', name: 'Aurora Sound', business: 'Aurora Sound Co.', country: 'US',
    description: 'Independent audio workshop building headphones and speakers that last.',
    products: [
      { cat: 'audio', name: 'Aurora Studio Headphones', brand: 'Aurora', stock: 25, description: 'Closed-back studio headphones with 40mm drivers and memory-foam cushions.', variants: [['AUR-HP-BLK', 'Matte Black', '149.00'], ['AUR-HP-SND', 'Sand', '149.00']] },
      { cat: 'audio', name: 'Aurora Mini Bluetooth Speaker', brand: 'Aurora', stock: 40, description: 'Pocket speaker with 12 hours playtime and IPX6 splash resistance.', variants: [['AUR-SPK-TEAL', 'Teal', '59.00'], ['AUR-SPK-CORAL', 'Coral', '59.00']] },
      { cat: 'accessories', name: 'Braided USB-C Cable 2m', brand: 'Aurora', stock: 120, description: 'Nylon braided 100W USB-C to USB-C cable.', variants: [['AUR-CBL-2M', '2 metres', '14.50']] },
      { cat: 'audio', name: 'Aurora Limited Edition Turntable', brand: 'Aurora', stock: 1, description: 'Hand-assembled belt-drive turntable. Only one unit available.', variants: [['AUR-TT-LTD', 'Walnut', '499.00']] },
    ],
  },
  {
    email: 'northwind@demo.marketflow.local', name: 'Northwind Outfitters', business: 'Northwind Outfitters', country: 'CA',
    description: 'Outdoor apparel and footwear designed in the Rockies.',
    products: [
      { cat: 'footwear', name: 'Ridge Trail Running Shoe', brand: 'Northwind', stock: 30, description: 'Lightweight trail shoe with a grippy lugged outsole.', variants: [['NW-RTR-42', 'EU 42', '119.00'], ['NW-RTR-43', 'EU 43', '119.00'], ['NW-RTR-44', 'EU 44', '119.00']] },
      { cat: 'apparel', name: 'Merino Base Layer Tee', brand: 'Northwind', stock: 50, description: '100% merino wool, odour resistant, 180 g/m².', variants: [['NW-MER-S', 'S', '64.00'], ['NW-MER-M', 'M', '64.00'], ['NW-MER-L', 'L', '64.00']] },
      { cat: 'sports', name: 'Packable Rain Shell', brand: 'Northwind', stock: 3, description: '2.5-layer waterproof shell that packs into its own pocket.', variants: [['NW-SHELL-M', 'M', '139.00']] },
    ],
  },
  {
    email: 'kitchenco@demo.marketflow.local', name: 'Kitchen & Co', business: 'Kitchen & Co', country: 'GB',
    description: 'Cookware and homeware for people who love to cook.',
    products: [
      { cat: 'cookware', name: 'Carbon Steel Skillet 28cm', brand: 'Kitchen & Co', stock: 18, description: 'Pre-seasoned carbon steel pan that gets better with use.', variants: [['KC-SKL-28', '28 cm', '54.00']] },
      { cat: 'cookware', name: 'Chef Knife 20cm', brand: 'Kitchen & Co', stock: 22, description: 'Forged stainless steel chef knife with full tang.', variants: [['KC-KNF-20', '20 cm', '89.00']] },
      { cat: 'decor', name: 'Stoneware Pour-Over Set', brand: 'Kitchen & Co', stock: 12, description: 'Hand-glazed dripper and carafe for slow mornings.', variants: [['KC-POUR-WHT', 'Chalk white', '42.00'], ['KC-POUR-MOS', 'Moss', '42.00']] },
      { cat: 'books', name: 'The Weeknight Cookbook', brand: 'Kitchen & Co Press', stock: 60, description: '120 recipes that take less than 30 minutes.', variants: [['KC-BOOK-WN', 'Hardcover', '24.99']] },
    ],
  },
];

const CUSTOMERS = [
  ['ada@demo.marketflow.local', 'Ada Moreno'],
  ['linus@demo.marketflow.local', 'Linus Okafor'],
];

async function main() {
  const admin = new Session('admin');
  const adminEmail = env('BOOTSTRAP_ADMIN_EMAIL') ?? env('MARKETFLOW_BOOTSTRAP_ADMIN_EMAIL') ?? 'admin@marketflow.local';
  const adminPassword = env('BOOTSTRAP_ADMIN_PASSWORD') ?? env('MARKETFLOW_BOOTSTRAP_ADMIN_PASSWORD');
  if (!adminPassword) {
    console.error('Set BOOTSTRAP_ADMIN_PASSWORD (in .env or the environment) to the bootstrap admin password.');
    process.exit(2);
  }
  await admin.login(adminEmail, adminPassword);
  log('admin logged in');

  const summary = [];
  for (const v of VENDORS) {
    const s = new Session(v.email);
    await s.register(v.email, PASSWORD, v.name);
    // apply (or find the existing application)
    let vendor = (await s.get('/api/v1/vendors/me', { expect: [200, 404] })).data;
    if (!vendor?.id) {
      vendor = (await s.post('/api/v1/vendors/applications', {
        businessName: v.business, description: v.description, contactEmail: v.email, country: v.country,
      })).data;
      log(`${v.business}: application submitted`);
    }
    if (vendor.status === 'PENDING') {
      await admin.post(`/api/v1/admin/vendors/${vendor.id}/approve`, {});
      log(`${v.business}: approved by admin`);
    }
    // the VENDOR role arrives asynchronously (vendor.events -> identity); refresh until it is in the token
    await until(`${v.business} VENDOR role`, async () => (await s.refresh()).user.roles.includes('VENDOR'));
    // catalog must also know the vendor is approved before products can be created
    await until(`${v.business} known to catalog`, async () =>
      (await s.get('/api/v1/vendor/products', { expect: [200, 403] })).status === 200);

    const existing = (await s.get('/api/v1/vendor/products?size=100')).data.items.map((p) => p.name);
    for (const p of v.products) {
      if (existing.includes(p.name)) continue;
      const created = (await s.post('/api/v1/vendor/products', {
        categoryId: CAT[p.cat], name: p.name, description: p.description, brand: p.brand, maxPerOrder: 5,
        variants: p.variants.map(([sku, name, price]) => ({ sku, name, price })),
      })).data;
      await s.post(`/api/v1/vendor/products/${created.id}/publish`);
      for (const variant of created.variants) {
        await until(`inventory item ${variant.sku}`, async () =>
          (await s.post('/api/v1/vendor/inventory/adjustments',
            { variantId: variant.id, delta: p.stock, reason: 'initial stock (demo seed)' },
            { expect: [200, 404] })).status === 200);
      }
      log(`${v.business}: published "${p.name}" (${created.variants.length} variant(s), stock ${p.stock} each)`);
    }
    summary.push(`${v.business} <${v.email}>`);
  }

  for (const [email, name] of CUSTOMERS) {
    await new Session(email).register(email, PASSWORD, name);
    summary.push(`customer ${name} <${email}>`);
  }
  log('demo data ready:\n  ' + summary.join('\n  ') + `\n  password for demo users: ${PASSWORD}`);
}

main().catch((e) => {
  console.error('Seeding failed:', e.message);
  process.exit(1);
});
