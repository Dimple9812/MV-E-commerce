# ADR 0004: Coordinate checkout with an orchestrated saga

Status: accepted (2026-10)

## Context

Checkout spans three services that each own their data: order (order and suborders), inventory (stock) and
payment (money). It must never take money without stock, never keep stock reserved for a failed payment and
never lose an order halfway. A distributed ACID transaction is not available.

## Decision

order-service runs an **orchestrated saga**, persisted in `checkout_saga` with an explicit state machine
(`SagaState`) and an append-only `saga_transition` history:

```
CREATED -> INVENTORY_PENDING -> INVENTORY_RESERVED -> PAYMENT_PENDING -> PAYMENT_AUTHORIZED
        -> INVENTORY_CONFIRM_PENDING -> CONFIRMED
failure paths -> COMPENSATING -> CANCELLED | FAILED ; payment uncertainty -> RECONCILIATION_REQUIRED
```

Payment is **authorised before capture**, and capture happens only after stock is reserved, so the common
failures need no refund. Compensations are recorded as pending work (release inventory, void authorisation,
refund) and the saga reaches CANCELLED only after each is acknowledged. Every step has a deadline;
`SagaTimeoutScanner` retries a step (the command is idempotent) a bounded number of times and then
compensates. The saga row is locked (`SELECT ... FOR UPDATE`) while a reply is applied, and replies for a step
the saga has moved past are ignored and counted.

Unknown payment outcomes (provider timeout) never cancel the order: they move to reconciliation, because the
customer may have been charged.

## Consequences

- One place shows the whole checkout, including why it was cancelled (admin saga view, `saga_*` metrics).
- The orchestrator is a single point of logic but not of failure: its state is in PostgreSQL, so a restart
  resumes where it stopped (tested in `CheckoutSagaIT` and verified live with SIGKILL).
- Reservations have a TTL in inventory as a safety net if the saga is lost entirely.

## Alternatives considered

- **Choreography** (each service reacts to the previous event): fewer moving parts per service, but the flow
  and its compensations are spread over five services and hard to observe or change.
- **Two-phase commit / XA**: not supported across Kafka and independent databases, and blocks on coordinator
  failure.
