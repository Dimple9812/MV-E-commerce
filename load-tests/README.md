# Load testing

k6 scripts that exercise MarketFlow through the gateway exactly as the browser does. All numbers below were
measured; nothing here is extrapolated. The raw k6 summaries are in [`results/`](results).

## Scripts

| Script | What it does |
|---|---|
| [`k6/browse.js`](k6/browse.js) | Anonymous read path: product list, full-text search, product detail, stock availability, with 0.25-0.75 s think time. Constant VUs. |
| [`k6/checkout.js`](k6/checkout.js) | Authenticated checkout at a constant arrival rate (one item, sandbox card `tok_visa`), then polls the order until the saga reaches a terminal state. **Creates real orders.** |
| [`prepare.mjs`](prepare.mjs) | Tops up stock of the SKUs the checkout test buys (default 5000 units each). |

```bash
node scripts/seed-demo.mjs && node load-tests/prepare.mjs
k6 run -e VUS=50 -e DURATION=2m load-tests/k6/browse.js
k6 run -e RATE=5 -e DURATION=2m load-tests/k6/checkout.js
```

The gateway rate-limits per user or IP (20 req/s, burst 40 by default). A load generator on one IP measures
the limiter, not the services, so for these runs the gateway was started with `RATE_LIMIT_RPS=2000
RATE_LIMIT_BURST=4000`. Leave the defaults in place anywhere else.

## Targets

| Path | Target | Why |
|---|---|---|
| Browse (list, search, detail) | p95 < 300 ms (search < 400 ms), errors < 1 % | Storefront pages render after one or two of these calls. |
| Checkout API (`POST /orders/checkout`) | p95 < 800 ms, errors < 1 % | Only validation, pricing and the outbox write are synchronous. |
| Checkout saga (accepted to CONFIRMED) | p95 < 5 s, no order left unfinished | The customer watches a progress screen. |

## Test environment (2026-10-11)

- One cloud VM: **2 vCPU** (Intel Xeon 2.8 GHz), **7.6 GB RAM**. The load generator, PostgreSQL 16, Kafka 3.9
  (single KRaft node), Redis 7 and all ten Spring Boot services ran on this same machine, competing for CPU.
- Services ran as plain JVM processes (not containers) with `-Xmx384m -XX:TieredStopAtLevel=1` (C1 JIT only,
  chosen to fit ten JVMs in memory). Production JVMs with full JIT and dedicated cores will behave differently.
- Data: the demo seed (3 sellers, 11 products) plus orders from earlier runs (about 3,000 orders).
- Tracing disabled; logs to files.

These conditions are far from a production deployment. Treat the results as a **baseline for regression
comparison** on the same setup and as evidence of behaviour under saturation, not as capacity figures.

## Results

### Browse (2 minutes per step)

| VUs | Throughput | Errors | p50 | p95 | Max | Targets |
|---|---|---|---|---|---|---|
| 20 | 146 req/s | 0 % (0 / 17,601) | 8 ms | 25 ms | 113 ms | met |
| 50 | 303 req/s | 0 % (0 / 36,569) | 31 ms | 101 ms | 337 ms | met |
| 100 | 336 req/s | 0 % (0 / 40,757) | 156 ms | 328 ms | 1,482 ms | **missed** (list p95 333 ms, detail p95 311 ms) |

Throughput flattens between 50 and 100 VUs at roughly 300-340 req/s: the machine is CPU-bound and extra VUs only
add queueing latency. No request failed at any step.

### Checkout

| Arrival rate | Outbox poll interval | Checkout API p95 | Saga p50 | Saga p95 | Confirmed | Unfinished | Targets |
|---|---|---|---|---|---|---|---|
| 2 / s | 500 ms | 44 ms | 2.45 s | 3.05 s | 241 / 241 | 0 | met |
| 5 / s | 500 ms | 180 ms | 3.69 s | 4.31 s | 598 / 598 (3 dropped by k6) | 0 | met |
| 2 / s | 100 ms | 51 ms | 0.82 s | 1.10 s | 241 / 241 | 0 | met |
| 5 / s | 100 ms | 64 ms | 0.85 s | 1.14 s | 601 / 601 | 0 | met |
| 10 / s | 100 ms | 1,152 ms | 7.3 s | 35.7 s | 157 confirmed, 251 still running after 15 s, 793 not started | 251 | **missed** |

## Findings and changes made because of them

1. **Gateway bulkhead tripped the circuit breaker under ordinary load.** The first 50-VU browse run failed 16 %
   of requests with 503. The gateway's fallback logged nothing, so a log line for the underlying cause was added
   (`FallbackController`), which showed `BulkheadFullException`: Spring Cloud CircuitBreaker puts a semaphore
   bulkhead of **25 concurrent calls** in front of every route, and its rejections counted as breaker failures,
   so the catalog breaker opened and shed all traffic for 10 s at a time. Fix: bulkhead sized to 150 concurrent
   calls per downstream, and `BulkheadFullException` ignored by the breaker (shedding excess load is not a sign
   the downstream is unhealthy). Result: 0 errors at 50 and 100 VUs.
2. **Idle pooled connections closed by Tomcat.** While investigating, the gateway also logged
   `Connection prematurely closed BEFORE response`: Tomcat's keep-alive timeout (10 s, inherited from the
   connection timeout) was shorter than the gateway pool's idle time. Fix: gateway pool `max-idle-time 20s`
   with background eviction, Tomcat `keep-alive-timeout 30s` so the client always closes first.
3. **Outbox polling dominated saga latency.** A confirmed checkout crosses eight outbox hops
   (order, inventory, order, payment, order, payment, order, inventory, then order again), and each waits on
   average half the poll interval. Lowering `marketflow.outbox.poll-interval` from 500 ms to 100 ms cut saga
   p95 from 4.31 s to 1.14 s at 5 checkouts/s. The cost is one indexed `SELECT ... FOR UPDATE SKIP LOCKED` per
   service every 100 ms when idle. 100 ms is now the default. A further step would be to wake the publisher
   right after commit (transaction synchronisation) instead of polling.
4. **Overload degrades, but loses nothing.** At 10 checkouts/s the CPU was saturated (load average about 12 on
   2 vCPUs); sagas queued in Kafka and completed late. One minute after the run every saga was in a terminal
   state (`saga_instances` showed only CONFIRMED and CANCELLED), the inventory ledger check
   (`GET /api/v1/admin/inventory/ledger-check`) reported no mismatch between stock counters and the movement
   ledger, and no stock counter was negative.

## Not yet measured

- Mixed browse and checkout traffic at the same time; spike and soak (multi-hour) tests.
- Contention on a single hot SKU under load (correctness is covered by `InventoryIT`'s 20-thread oversell
  test, not by k6).
- Behaviour with several replicas per service, in containers with CPU limits, and with tracing enabled.
