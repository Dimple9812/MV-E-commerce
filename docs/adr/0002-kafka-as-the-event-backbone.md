# ADR 0002: Use Kafka for domain events and saga commands

Status: accepted (2026-10)

## Context

Services must react to each other's state changes (an approved vendor gets the VENDOR role; a confirmed
order creates vendor earnings and notifications) and the checkout saga must drive inventory and payment.
Synchronous HTTP between services would chain their availability: checkout would fail whenever payment is
restarting.

## Decision

Apache Kafka (KRaft mode, no ZooKeeper) carries all inter-service communication except the browser-facing
API:

- **Domain events** per aggregate (`order.events`, `payment.events`, `vendor.events`, ...), keyed by aggregate
  id so all events of one order or product stay ordered in one partition.
- **Commands** from the saga orchestrator on dedicated topics (`inventory.commands`, `payment.commands`), so
  a command's consumer is explicit and the command can be redelivered safely.
- JSON envelope with versioned, documented payload schemas (`contracts/events`).
- Producers: `acks=all`, idempotence on. Consumers: manual per-record acknowledgement, `read_committed`,
  bounded retries with backoff, then a dead-letter topic per source topic.

## Consequences

- Services keep working while others are down; work queues up in Kafka and drains on recovery (verified by
  killing order-service mid-checkout, see docs/implementation-status.md).
- Read models (catalog availability, analytics, vendor earnings) are rebuilt from events, so a new consumer
  can be added later and catch up from retained history.
- Kafka's guarantee is at-least-once delivery, so every consumer must be idempotent (ADR 0005).
- Ordering holds only within a partition: cross-aggregate ordering is never assumed, and per-aggregate
  version fields (`productVersion`, `vendorVersion`, `stockVersion`) let consumers discard stale events.

## Alternatives considered

- **RabbitMQ**: good for commands, weaker for replayable event history and rebuilding projections.
- **HTTP calls with retries**: couples availability and makes retries of non-idempotent calls dangerous.
- **Change data capture (Debezium)**: a strong alternative to the polling outbox publisher, at the price of
  running Kafka Connect; noted as a future improvement.
