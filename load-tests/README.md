# Load testing

k6 scripts that exercise MarketFlow through the gateway exactly as the browser does. All numbers below were
measured; nothing here is extrapolated. The raw k6 summaries are in [`results/`](results).

## Scripts

| Script | What it does |
|---|---|
| [`k6/browse.js`](k6/browse.js) | Anonymous read path: product list, full-text search, product detail, stock availability, with 0.25-0.75 s think time. Constant VUs. |
| [`k6/checkout.js`](k6/checkout.js) | Authenticated checkout at a constant arrival rate (one item, sandbox card `tok_visa`), then polls the order until the saga reaches a terminal state. **Creates real orders.** |
| [`prepare.mjs`](prepare.mjs) | Tops up stock of the SKUs the checkout test buys (default 5000 units each). |
| [`k6/chat.js`](k6/chat.js) | Chat over real STOMP/WebSocket sessions. `MODE=messaging`: buyer/shop pairs, the buyer sends every 2 s, measures delivery latency to the shop on the other instance. `MODE=capacity`: holds N idle sessions on one instance. See [Chat](#chat). |
| [`prepare-chat.mjs`](prepare-chat.mjs) | Creates buyer accounts, approved shops and one conversation per pair for the chat test (needs the bootstrap admin). |

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

## Chat

Measured on 2026-10-19 on the same kind of machine as above (**2 vCPU, 7.6 GB**, one cloud VM). Everything ran
on it as plain JVM processes: PostgreSQL 16, Kafka 3.9 (single KRaft node), Redis 7, identity-service,
vendor-service, the gateway, **two chat-service instances**, and k6 itself. JVM flags as above
(`-Xmx384m -XX:TieredStopAtLevel=1`) unless stated otherwise. The gateway and identity login throttle were raised
for the load generator's single IP (`RATE_LIMIT_RPS`, `LOGIN_ATTEMPTS_PER_IP_PER_MINUTE`); chat-service's own
per-user limit (20 messages / 10 s) stayed in force, and the test stays under it.

```bash
BOOTSTRAP_ADMIN_EMAIL=... BOOTSTRAP_ADMIN_PASSWORD=... node load-tests/prepare-chat.mjs 200
k6 run -e PAIRS=200 -e DURATION=2m -e SUMMARY=load-tests/results/chat-200-pairs.json load-tests/k6/chat.js
k6 run -e MODE=capacity -e CONNS=2500 -e TARGET=ws://127.0.0.1:8091/ws/chat load-tests/k6/chat.js
```

**What is measured.** Each pair is one buyer and one shop. The buyer's STOMP session goes through the gateway to
chat-service #1, the shop's goes directly to chat-service #2, so every message takes the full path: gateway →
#1 (access check, rate limit, contact filter) → PostgreSQL commit (message + outbox) → Redis pub/sub → #2 →
shop's socket. `chat_delivery_ms` is the time from the buyer's SEND frame to the shop receiving the message;
`chat_ack_ms` is the time until the buyer's own session gets it back (the "sent" tick). "Lost" means not delivered
within 5 s after sending stopped. The client and server clocks are the same machine's.

### Delivery latency (each buyer sends one message every 2 s for 2 minutes)

| Pairs (sockets) | Messages | Rate | Delivery p50 | p95 | p99 | Max | Ack p95 | Lost |
|---|---|---|---|---|---|---|---|---|
| 50 (100) | 2,950 | 25 / s | 14 ms | 40 ms | 328 ms | 465 ms | 44 ms | 0 |
| 100 (200) | 5,900 | 49 / s | 14 ms | 45 ms | 79 ms | 159 ms | 46 ms | 0 |
| 200 (400), run 1 | 11,716 | 98 / s | 62 ms | 225 ms | 305 ms | 447 ms | 228 ms | 0 |
| 200 (400), run 2 | 11,800 | 98 / s | 14 ms | 44 ms | 61 ms | 103 ms | 45 ms | 0 |
| 100 pairs while #1 also holds 2,000 idle sessions | 5,900 | 49 / s | 18 ms | 80 ms | 106 ms | 143 ms | 97 ms | 0 |

An earlier 200-pair run (before a fix to how the script records `chat_ack_ms`; delivery was measured the same way)
gave delivery p95 79 ms, p99 145 ms, 0 lost of 11,800. Run-to-run spread at 200 pairs (p95 44-225 ms) comes from
sharing two vCPUs between eight JVMs, PostgreSQL, Kafka and the load generator; the tail moves with GC pauses and
CPU contention, not with message volume. No message was lost in any run (38,266 messages in the
table, 50,066 including the earlier run). Raw summaries: `results/chat-*.json`.

### Connections per instance (idle authenticated sessions held 60 s on chat-service #1)

| Heap | Sessions | Connected | Held to the end | Heap after full GC | Per session |
|---|---|---|---|---|---|
| 384 MB | 1,000 | 100 % | 100 % | 60 → 152 MB | 92 KB |
| 384 MB | 2,500 | 100 % | 100 % | 60 → 294 MB | 94 KB |
| 384 MB | 4,000 | 54 % of attempts | 0 % | out of memory at about 3,300; the instance exited (`ExitOnOutOfMemoryError`), #2 kept serving | |
| 1 GB | 6,000 | 100 % | 100 % | 45 → 626 MB | 97 KB |
| 1 GB | 8,000 | 100 % | 100 % | 58 → 810 MB | 94 KB |
| 1 GB | 9,000 | stalled above about 8,000 | | handshakes beyond Tomcat's `max-connections` (8,192) time out | |

So one instance held **2,500 sessions with the 384 MB heap used for all services here, and 8,000 with a 1 GB
heap**; 8,192 is a hard ceiling per instance until `CHAT_MAX_CONNECTIONS` (`server.tomcat.max-connections`) is
raised together with the heap. Heap read after `jcmd GC.run` through `/actuator/prometheus`; samples in
`results/chat-capacity-samples.txt`.

The STOMP CONNECT time in the capacity runs (p95 1.2 s at 1,000, 3.7 s at 2,500, about 11 s at 6,000-8,000) measures
a deliberate connection storm (thousands of sessions opened within 2-3 s by k6 on the same two vCPUs), not a
user's connect time; in the messaging runs it was 0.4-1.8 s while hundreds of sessions connected at once.

### Findings

1. **Memory per connection is the servlet container's, not chat-service's.** A class histogram at 2,000 sessions
   (`jcmd GC.class_histogram`) showed about 96 MB in `char[]` (Tomcat's 8 K-character text buffer per WebSocket) and
   most of the rest in `byte[]` read/write buffers. Shrinking those buffers would refuse STOMP frames carrying a
   2,000-character message with JSON escaping, so they were left at their defaults and the limit is documented
   instead: plan about 95 KB of heap per connected user.
2. **The connection ceiling had to be made explicit.** Tomcat's default `max-connections` (8,192) silently capped
   sessions per instance; it is now set in chat-service's configuration with a comment tying it to heap size.
3. **Losing an instance under memory pressure did not lose messages elsewhere.** When #1 died at about 3,300
   sessions, #2 kept serving its users; PostgreSQL holds every message, and clients resynchronise on reconnect.

## Not yet measured

- Mixed browse and checkout traffic at the same time; spike and soak (multi-hour) tests.
- Contention on a single hot SKU under load (correctness is covered by `InventoryIT`'s 20-thread oversell
  test, not by k6).
- Behaviour with several replicas per service, in containers with CPU limits, and with tracing enabled.
- Chat: image uploads under load, more than two chat-service instances, and Redis or an instance failing during a
  messaging run (covered functionally by the resync logic and `ChatWebSocketIT`, not measured with k6).
