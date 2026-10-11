#!/usr/bin/env node
// Tops up stock of the SKUs the checkout load test buys, so the test measures the happy path rather than
// running out of stock. Uses the seeded demo sellers (development-only password).
//   node load-tests/prepare.mjs [units]      default 5000 units per SKU
import { Session, log } from '../scripts/lib/api.mjs';

const UNITS = Number(process.argv[2] ?? 5000);
const PASSWORD = process.env.VENDOR_PASSWORD ?? 'DemoPass2026!';
const PLAN = {
  'aurora@demo.marketflow.local': ['AUR-CBL-2M', 'AUR-SPK-TEAL', 'AUR-SPK-CORAL'],
  'kitchenco@demo.marketflow.local': ['KC-BOOK-WN'],
};

for (const [email, skus] of Object.entries(PLAN)) {
  const s = await new Session(email).login(email, PASSWORD);
  const items = (await s.get('/api/v1/vendor/inventory?size=200')).data.items;
  for (const sku of skus) {
    const item = items.find((i) => i.sku === sku);
    if (!item) throw new Error(`${sku} not found for ${email}; run scripts/seed-demo.mjs`);
    const need = UNITS - item.available;
    if (need > 0) {
      await s.post('/api/v1/vendor/inventory/adjustments', { variantId: item.variantId, delta: need, reason: 'load test top-up' });
    }
    log(`${sku}: available ${Math.max(UNITS, item.available)}`);
  }
}
