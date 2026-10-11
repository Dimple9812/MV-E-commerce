// Read path through the gateway: product listing, full-text search, product detail, availability.
//   k6 run load-tests/k6/browse.js                       (defaults: 20 VUs for 2 minutes)
//   k6 run -e VUS=50 -e DURATION=5m -e BASE=http://host:8080 load-tests/k6/browse.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const searchLatency = new Trend('search_duration', true);
const detailLatency = new Trend('product_detail_duration', true);
const TERMS = ['headphones', 'skillet', 'shoe', 'cable', 'cookbook', 'merino', 'knife', 'speaker'];

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '2m',
    },
  },
  thresholds: {
    // targets from load-tests/README.md
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:list}': ['p(95)<300'],
    search_duration: ['p(95)<400'],
    product_detail_duration: ['p(95)<300'],
  },
};

export function setup() {
  const res = http.get(`${BASE}/api/v1/products?size=50`);
  const ids = res.json('items').map((p) => p.id);
  if (!ids.length) throw new Error('no published products: run scripts/seed-demo.mjs first');
  return { ids };
}

export default function ({ ids }) {
  const list = http.get(`${BASE}/api/v1/products?page=0&size=24&sort=newest`, { tags: { endpoint: 'list' } });
  check(list, { 'list 200': (r) => r.status === 200 });

  const term = TERMS[Math.floor(Math.random() * TERMS.length)];
  const search = http.get(`${BASE}/api/v1/products?q=${term}&size=24`, { tags: { endpoint: 'search' } });
  check(search, { 'search 200': (r) => r.status === 200 });
  searchLatency.add(search.timings.duration);

  const id = ids[Math.floor(Math.random() * ids.length)];
  const detail = http.get(`${BASE}/api/v1/products/${id}`, { tags: { endpoint: 'detail' } });
  check(detail, { 'detail 200': (r) => r.status === 200 });
  detailLatency.add(detail.timings.duration);

  if (detail.status === 200) {
    const variantIds = detail.json('variants').map((v) => v.id).join(',');
    const avail = http.get(`${BASE}/api/v1/inventory/availability?variantIds=${variantIds}`, { tags: { endpoint: 'availability' } });
    check(avail, { 'availability 200': (r) => r.status === 200 });
  }
  sleep(Math.random() * 0.5 + 0.25); // think time
}
