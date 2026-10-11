# ADR 0003: Publish events through a transactional outbox

Status: accepted (2026-10)

## Context

A service that writes to its database and then publishes to Kafka has two independent writes. If the process
crashes between them, or Kafka is unavailable, the database says "order created" but no event exists: the
saga never starts and the order is stuck. Publishing first and writing second is worse: consumers act on an
order that may then fail to commit. There is no distributed transaction between PostgreSQL and Kafka.

## Decision

Every service writes events into its own `outbox_event` table **in the same database transaction** as the
business change (`OutboxWriter`, which requires an existing transaction: `Propagation.MANDATORY`). A
scheduled `OutboxPublisher` then:

1. selects pending rows `FOR UPDATE SKIP LOCKED` (several instances never publish the same row concurrently),
2. sends each to Kafka keyed by aggregate id and **waits for the broker acknowledgement**,
3. marks the row published, or schedules a retry with exponential backoff; after the maximum attempts the row
   is parked as `FAILED`, counted by the `outbox_failed` gauge, and alerted on,
4. deletes published rows after a retention period.

## Consequences

- An event exists if and only if the business change committed.
- Delivery is at-least-once: a crash after the send but before marking the row re-sends it. Consumers
  deduplicate on `eventId` (ADR 0005), so this is harmless.
- Latency: an event waits up to one poll interval. With 8 outbox hops per confirmed checkout this dominated
  saga time; measured p95 went from 4.3 s (500 ms interval) to 1.1 s (100 ms), see load-tests/README.md.
- Per-aggregate order is preserved because rows are published in id order and keyed by aggregate.

## Alternatives considered

- **Kafka transactions with a chained DB transaction**: still not atomic across the two systems.
- **CDC (Debezium) on the outbox table**: lower latency and no polling, more infrastructure.
- **Listen/notify to wake the publisher immediately**: a cheap improvement on top of polling, not done yet.
