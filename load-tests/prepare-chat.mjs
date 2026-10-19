#!/usr/bin/env node
// Creates the buyers, approved shops and conversations the chat load test uses, through the gateway like the app.
// Each pair is one buyer and one shop with an open conversation between them; the k6 script signs both in.
//   BOOTSTRAP_ADMIN_EMAIL=... BOOTSTRAP_ADMIN_PASSWORD=... node load-tests/prepare-chat.mjs [pairs]   default 100
// Writes load-tests/.chat-pairs.json (git-ignored): e-mails, the shared test password and conversation ids.
import { writeFileSync } from 'node:fs';
import { Session, log, until } from '../scripts/lib/api.mjs';

const PAIRS = Number(process.argv[2] ?? 100);
const PASSWORD = process.env.LOAD_PASSWORD ?? 'LoadTest2026!x';
const CONCURRENCY = Number(process.env.CONCURRENCY ?? 8);
const adminEmail = process.env.BOOTSTRAP_ADMIN_EMAIL;
const adminPassword = process.env.BOOTSTRAP_ADMIN_PASSWORD;
if (!adminEmail || !adminPassword) {
  console.error('Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD (the admin approves the test shops).');
  process.exit(2);
}

const admin = await new Session('admin').login(adminEmail, adminPassword);

async function pair(i) {
  const n = String(i).padStart(4, '0');
  const sellerEmail = `chat-seller-${n}@load.marketflow.local`;
  const buyerEmail = `chat-buyer-${n}@load.marketflow.local`;

  const seller = await new Session(sellerEmail).register(sellerEmail, PASSWORD, `Load Seller ${n}`);
  let vendor = (await seller.get('/api/v1/vendors/me', { expect: [200, 404] })).data;
  if (!vendor?.id) {
    vendor = (await seller.post('/api/v1/vendors/applications', {
      businessName: `Load Test Shop ${n}`, description: 'Chat load test shop', contactEmail: sellerEmail, country: 'US',
    })).data;
  }
  if (vendor.status === 'PENDING') await admin.post(`/api/v1/admin/vendors/${vendor.id}/approve`, {});

  const buyer = await new Session(buyerEmail).register(buyerEmail, PASSWORD, `Load Buyer ${n}`);
  // chat-service learns about the approval from vendor.events; until then the shop is "not found" there
  let conversationId = null;
  await until(`shop ${n} known to chat-service`, async () => {
    const r = await buyer.call('POST', '/api/v1/chat/conversations', { vendorId: vendor.id }, { expect: [200, 201, 404] });
    if (r.status === 404) return false;
    conversationId = r.data.id;
    return true;
  }, { timeoutMs: 60000 });
  return { buyerEmail, sellerEmail, vendorId: vendor.id, conversationId };
}

const out = new Array(PAIRS);
let next = 0;
await Promise.all(Array.from({ length: CONCURRENCY }, async () => {
  while (next < PAIRS) {
    const i = next++;
    out[i] = await pair(i + 1);
    if ((i + 1) % 25 === 0) log(`${i + 1} / ${PAIRS} pairs ready`);
  }
}));
writeFileSync(new URL('./.chat-pairs.json', import.meta.url), JSON.stringify({ password: PASSWORD, pairs: out }, null, 1));
log(`wrote load-tests/.chat-pairs.json with ${PAIRS} pairs`);
