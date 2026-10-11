# Interview guide

How to explain MarketFlow, what to say about each design decision, and where in the code the answer lives.
Everything here describes code that exists in this repository. Numbers are either measured (with the
environment stated) or explicitly marked as targets.

## 1. The 5-minute explanation

> MarketFlow is a multi-vendor marketplace: independent sellers list products, a customer buys from several
> of them in one checkout, and each seller ships their own part. It's ten Spring Boot services on Java 21 with
> Kafka, PostgreSQL and Redis, plus a React frontend with a storefront, a seller hub and an admin console.
>
> The hard part is checkout. One order touches three services that each own their data: order-service, which
> splits the order into one suborder per seller, inventory-service, which owns stock, and payment-service. There
> is no transaction across them, so order-service runs an **orchestrated saga**: reserve stock, authorise the
> payment, capture, confirm stock. If a step fails it runs compensations in reverse: release stock, void the
> authorisation, refund. The saga state lives in PostgreSQL, so a crash doesn't lose it; I tested that by killing
> order-service mid-checkout.
>
> Three mechanisms make that reliable. Every event is written to an **outbox table in the same transaction**
> as the business change and published afterwards, so we never commit without the event. Every consumer is
> **idempotent**: it records the event id in the same transaction as its update, because Kafka is at-least-once.
> And stock is changed with a **conditional UPDATE** (`available - n >= 0`), so twenty concurrent buyers of the
> last unit produce exactly one sale.
>
> The part I like most is payment uncertainty: if the provider times out we don't know whether the card was
> charged, so the saga doesn't cancel; it waits for a reconciliation job that asks the provider. Late answers
> are voided or refunded automatically.
>
> It's tested with Testcontainers against real PostgreSQL and Redis and embedded Kafka, including all the failure
> paths, plus browser tests with Playwright and k6 load tests. Load testing actually found two real bugs in the
> gateway's resilience configuration, which I can talk about.

## 2. The 15-minute system design walkthrough

1. **Requirements** (1 min). Multi-vendor cart, one payment, per-seller fulfilment and earnings; never charge
   without stock, never oversell, never duplicate orders, survive service restarts.
2. **Services and data ownership** (3 min). Draw the gateway, nine services, one database each. Explain
   projections: order-service keeps `product_projection` and `vendor_projection` built from events, so checkout
   never calls catalog synchronously (ADR 0006). Shared library has infrastructure only.
3. **Communication** (2 min). Browser to gateway over HTTPS/JSON; service to service only through Kafka:
   domain events (`order.events`, keyed by order id) and saga commands (`inventory.commands`,
   `payment.commands`). Why commands on their own topics: one explicit consumer, idempotent by design.
4. **Checkout saga** (4 min). Walk the sequence diagram in the README. Emphasise: authorise before reserve is
   confirmed, capture only after stock is held, so the common failures (out of stock, decline) need no refund.
   Show the state machine and the deadline/retry/compensate loop.
5. **Reliability mechanisms** (3 min). Outbox, consumer dedupe, idempotency key on checkout, conditional stock
   updates, reconciliation of unknown payment outcomes, DLT with replay.
6. **Operations** (2 min). Metrics per saga state and compensation reason, outbox backlog gauges, DLT register,
   tracing with correlation id in every event, runbooks linked from alerts.

## 3. The 30-minute deep dive (outline)

| Minutes | Topic | Open these files |
|---|---|---|
| 0-5 | Context, domain, service map | `README.md`, `docs/adr/0001` |
| 5-12 | Saga: states, transitions, compensations, timeouts | `order-service/.../domain/saga/SagaState.java`, `CheckoutSagaOrchestrator.java`, `SagaTimeoutScanner.java` |
| 12-17 | Outbox and inbox | `libs/platform-core/.../outbox/OutboxWriter.java`, `OutboxPublisher.java`, `inbox/InboxGuard.java`, `kafka/EventListenerSupport.java` |
| 17-21 | Inventory concurrency | `inventory-service/.../application/StockLedger.java`, `ReservationService.java`, `InventoryIT.concurrentCheckoutsCannotOversellTheLastUnit` |
| 21-25 | Payment uncertainty and webhooks | `payment-service/.../application/PaymentService.java`, `provider/ProviderGateway.java`, `webhook/WebhookSignature.java` |
| 25-28 | Security: JWT, refresh rotation, vendor isolation | `identity-service/.../RefreshTokenService.java`, `gateway-service/.../SecurityConfig.java`, ownership checks in services |
| 28-30 | What load testing found; what I would do next | `load-tests/README.md` |

## 4. Topic-by-topic answers

### Kafka partitioning and ordering

- Every message is keyed by its aggregate id (`outbox_event.msg_key`): order events by order id, stock events
  by variant id, vendor events by vendor id. Kafka guarantees order within a partition, so all events of one
  order arrive in order at a consumer.
- Nothing assumes order **across** aggregates. Where a consumer could see an old snapshot after a new one
  (for example after a replay), snapshots carry a version (`productVersion`, `vendorVersion`, `stockVersion`) and
  the consumer applies only newer versions (`ProductRepository.updateVariantStock` guards on `stock_version`).
- Three partitions per topic by default; consumer groups per purpose (`order-service.saga-payment`,
  `catalog-service.stock`) so one slow projection doesn't delay another.
- Manual per-record acknowledgement: the offset is committed only after the handler's database transaction
  committed.

### At-least-once delivery and duplicates

- Duplicates come from: outbox re-sends after a crash, consumer crash before commit, rebalances, deliberate
  replays.
- **Producer idempotence** only removes duplicates caused by the producer's own retries inside one session.
  It does not make processing exactly-once and I don't claim that.
- **Consumer dedupe**: `InboxGuard` inserts `(consumer, event_id)` into `processed_event` with
  `ON CONFLICT DO NOTHING` inside the handler's transaction. If the insert affects 0 rows, the event was already
  processed and the handler returns. If the handler fails, the insert rolls back with it, so a retry runs fully.
- **Business idempotency** for intents that arrive as different messages: checkout `Idempotency-Key` with a
  request hash (same key and different body is rejected with 422); one reservation per order; provider
  idempotency keys like `pay:<orderId>:capture`; refund amount bounded by what was captured; notification
  `dedupe_key`.

### Transactional outbox

- `OutboxWriter.append` uses `Propagation.MANDATORY`: it refuses to run outside the business transaction, so a
  developer cannot accidentally write an event without the change or vice versa.
- `OutboxPublisher` polls every 100 ms: `SELECT ... WHERE status='PENDING' AND next_attempt_at <= now() ORDER BY id
  LIMIT n FOR UPDATE SKIP LOCKED`, sends each record and **waits for the ack**, marks it published. Failures back
  off exponentially; after the limit the row is `FAILED` and the `outbox_failed` gauge alerts.
- `SKIP LOCKED` lets several replicas publish concurrently without double-sending the same row; ordering per
  aggregate is still kept because rows are processed in id order and a replica holds the lock for a batch.
- Why not "write then publish": a crash between the two loses the event; why not "publish then write": the
  event can describe a change that never committed.

### Saga states and compensation

- Happy path: `CREATED -> INVENTORY_PENDING -> INVENTORY_RESERVED -> PAYMENT_PENDING -> PAYMENT_AUTHORIZED ->
  INVENTORY_CONFIRM_PENDING -> CONFIRMED`.
- `SagaState` defines the legal transitions; an illegal one throws `IllegalSagaTransitionException`
  (`SagaStateTest.cannotSkipSteps`, `terminalStatesAreFinal`, `paymentUncertaintyNeverJumpsStraightToCancelled`).
- Compensation is bookkeeping, not a fire-and-forget: the saga stores the pending compensations
  (`INVENTORY`, `PAYMENT_VOID`, `PAYMENT_REFUND`), sends the commands, and moves to CANCELLED only when every one
  is acknowledged. A compensation that keeps failing ends in `FAILED` for an operator.
- Each reply is applied with the saga row locked (`findForUpdate`). A reply for a state the saga has left is
  ignored and counted (`saga_ignored_replies_total`), which is how duplicates and late replies stay harmless.

### Database transaction boundaries

- One local transaction per command or event: business change + outbox rows + `processed_event` row. Nothing
  else is atomic and the design never needs it to be.
- The checkout endpoint's transaction covers: idempotency lookup, re-pricing from local projections, order,
  suborders, items, saga row, and two outbox events. It does not call any other service.
- `EventListenerSupport.handleOnce` opens the transaction with a `TransactionTemplate` rather than
  `@Transactional` on the listener, because the dedupe must be inside the same transaction as the handler and
  self-invocation would bypass the proxy.
- Optimistic locking (`@Version`) on aggregates edited by people (product, vendor, order) to catch lost updates;
  pessimistic `FOR UPDATE` on the saga row where message handlers race.

### Inventory concurrency control

- `UPDATE inventory_item SET available = available - :q, reserved = reserved + :q ... WHERE variant_id = :v AND
  available - :q >= 0 RETURNING ...`. The database serialises concurrent updates on the row and evaluates the
  condition against the committed value, so there is no read-then-write race.
- `CHECK (available >= 0)` as a second guard; `stock_movement` ledger for every change and a ledger check
  endpoint that compares sums with counters.
- Multi-line orders reserve all lines in one transaction (all or nothing). Reservation unique per order,
  TTL 15 minutes with an expiry scanner, tombstone when a release overtakes its reservation.
- Proof: `InventoryIT.concurrentCheckoutsCannotOversellTheLastUnit` (20 threads, 1 unit, exactly one wins).

### JWT authentication and vendor isolation

- RS256: only identity-service has the private key; everything else validates with JWKS. Tokens carry `sub`
  and `roles`; 15 minutes. Refresh token rotation with family revocation on reuse (`IdentityApiIT.refreshRotatesAndReuseRevokesTheWholeFamily`).
- Gateway rules are coarse; services decide with ownership: a vendor's requests resolve the vendor id **from the
  token's user id** server-side (`vendorOf(userId)`), never from a request parameter, so a vendor cannot address
  another vendor's product or suborder. A pending vendor whose token still says VENDOR is refused because the
  service checks the vendor's status (`VendorApiIT.pendingVendorCannotUseVendorPortalEvenWithVendorRoleClaim`).
- Analytics for sellers filters by the vendor linked to the caller; admins see everything.

### Redis caching and invalidation

- Catalog product detail uses cache-aside (`CacheAside`): read cache, on miss load under a short per-key lock to
  avoid a stampede, cache with a TTL plus jitter; negative caching for missing products.
- Invalidation **after commit** (transaction synchronisation) plus a second delete one second later, to close the
  window where a concurrent reader re-caches the old value between commit and delete.
- What is not cached: anything about accounts or authorisation decisions; prices at checkout always come from
  order-service's projection, never from the cache.
- Redis failure is not fatal: the cache fails open to the database; login throttling fails open (logged).
- Test: `CatalogApiIT.productDetailIsCachedAndInvalidatedOnPriceChange`.

### Failure recovery

- Service crash: state is in PostgreSQL, unacknowledged Kafka messages are redelivered, outbox rows are
  re-published, deadlines trigger retries. Verified live: SIGKILL of order-service during `PAYMENT_PENDING`
  with payment-service also down; after both restarted the order confirmed.
- Kafka down: writes keep succeeding (outbox grows), publish resumes later.
- Provider down: circuit breaker opens, payments go to `UNKNOWN`/pending and are reconciled.
- Poison message: bounded retries, then DLT; consumers continue with the next message.

### Monitoring and debugging

- A customer reports "my order is stuck": admin order page shows the saga transitions with trigger events;
  correlation id from the order leads to traces in Jaeger and log lines in every service.
- Dashboards: saga states, compensations by reason, step failures, payment outcomes, outbox backlog, consumer
  lag, dead letters. Alerts link runbooks.

### Horizontal scaling

- Services are stateless (sessions are JWTs, state in PostgreSQL/Redis/Kafka) and scale with replicas; Kafka
  consumer groups spread partitions over replicas, so partitions cap consumer parallelism (3 by default; raise
  for production before data accumulates, since repartitioning changes key placement).
- Outbox publishers on several replicas cooperate through `SKIP LOCKED`. The saga timeout scanner may run on
  every replica: `onTimeout` takes the saga row lock and re-checks the deadline, so a saga is retried once even if
  two replicas pick it up. Payment reconciliation applies a provider answer under the payment row lock and every
  provider call carries an idempotency key, so concurrent reconcilers at worst ask the provider twice. Leader
  election (ShedLock) would remove the duplicated polling work; not needed for correctness.
- The gateway is reactive (Netty) and scales horizontally; its rate limiter state is in Redis.

### Performance bottlenecks (measured)

On a 2-vCPU VM running everything (k6, ten JVMs, PostgreSQL, Kafka, Redis):

- Read path flattened at about 300-340 requests/s; p95 at 50 VUs was 101 ms, at 100 VUs 328 ms, no errors.
- Saga latency was dominated by outbox polling: eight hops each waiting about half the poll interval. 500 ms
  interval: p95 4.31 s at 5 checkouts/s; 100 ms: p95 1.14 s.
- Load testing found two configuration bugs: Spring Cloud CircuitBreaker's default bulkhead (25 concurrent
  calls) rejected requests and those rejections opened the circuit breaker, and pooled connections outlived
  Tomcat's keep-alive. Both fixed and re-measured (`load-tests/README.md`).

These are a baseline on constrained hardware, not production capacity figures.

### Trade-offs versus a modular monolith

- A monolith would make checkout one ACID transaction and remove the outbox, sagas and projections entirely; it is
  cheaper to build, test and run. For a small team I would start there and split along these same boundaries
  when scaling or team ownership demands it.
- Microservices bought: independent deployment and failure isolation (review-service down does not affect
  checkout), separate scaling of the read-heavy catalog, and data ownership enforced by the database.
- Microservices cost: eventual consistency to explain to users ("figures can trail"), ten deployables,
  distributed debugging, and duplicate/ordering handling everywhere.

## 5. Common questions and answers

**Is this exactly-once?** No. Kafka delivers at least once; the system gets effectively-once *effects* by making
every consumer and every mutating API idempotent. Producer idempotence alone doesn't give that.

**Why orchestration rather than choreography?** The checkout has compensations that depend on how far it got.
With choreography that logic is spread over five services and nobody owns the overall state. With an
orchestrator I can show a customer exactly where their order is and test every path in one place.

**What if the orchestrator crashes?** Its state is a database row; replies wait in Kafka; the timeout scanner
re-drives anything past its deadline. Commands are idempotent, so re-sending is safe.

**What if a payment times out?** We don't know if money moved, so we neither cancel nor confirm: the saga waits
in `RECONCILIATION_REQUIRED` while payment-service asks the provider with the same idempotency key. If it
never resolves, the order fails as `PAYMENT_UNRESOLVED` and any late authorisation is voided automatically.

**How do you stop a double click creating two orders?** The browser sends one `Idempotency-Key` per checkout
attempt; the database has a unique constraint on `(customer_id, idempotency_key)`; a concurrent duplicate hits
the constraint and is answered with the existing order. Same key with a different body is rejected.

**How do you prevent overselling?** Conditional update in one SQL statement, check constraints, and the ledger.
Not with application-level locks or by reading the catalog's stock number, which is only a display projection.

**Why is the stock shown on the product page sometimes off?** It is a projection updated from
`StockLevelChanged` events, eventually consistent. Checkout never trusts it.

**How would you replay events?** `scripts/dlt-replay.mjs` re-publishes the original key and value to the original
partition; consumers dedupe on event id, so replay is safe. Fix the cause first.

**How do you evolve an event schema?** Add optional fields within a version; consumers ignore unknown fields and
skip unknown event types; breaking changes get a new `schemaVersion`, and consumers dead-letter versions they do
not understand rather than misreading them (`contracts/events/README.md`).

**Why is the access token not in localStorage?** XSS could read it. It lives in memory; the long-lived refresh
token is an HttpOnly cookie restricted to the auth path.

**What would break first under load?** On the test machine CPU. In production, likely hot SKUs (row lock
contention on one inventory row) and partition count limiting consumer parallelism.

**What would you do next?** Commit-triggered outbox publishing or CDC, a real PSP adapter, partition count sized
for production, contract tests generated from the schemas.

## 6. Resume description (sample)

**MarketFlow: event-driven multi-vendor marketplace** (Java 21, Spring Boot 3, Kafka, PostgreSQL, Redis, React,
TypeScript, Docker, Kubernetes/Helm, GitHub Actions)

- Designed and built ten Spring Boot microservices with database-per-service ownership, communicating through
  Kafka domain events and commands documented as versioned JSON schemas.
- Implemented an orchestrated, persisted checkout saga across order, inventory and payment with compensations
  (stock release, authorisation void, refund) and reconciliation of unknown payment outcomes.
- Guaranteed no lost events and no duplicate effects with a transactional outbox, transactional consumer
  deduplication and idempotency keys; prevented overselling with conditional SQL updates verified by a
  20-thread concurrency test.
- Covered the 18 critical business and failure scenarios with integration tests on Testcontainers and embedded
  Kafka, plus Playwright browser tests of the checkout, seller fulfilment and admin flows.
- Load-tested with k6; diagnosed a gateway bulkhead that tripped circuit breakers under load and outbox polling
  that dominated saga latency, and cut checkout saga p95 from 4.3 s to 1.1 s on the local benchmark setup.

Only use the last bullet's numbers together with their context ("local 2-vCPU benchmark"); do not present
them as production results.
