# ADR 0005: Every consumer and every mutating API is idempotent

Status: accepted (2026-10)

## Context

Kafka delivers at least once: redelivery happens after rebalances, consumer crashes before offset commit,
outbox re-sends and deliberate DLT replays. HTTP clients retry too (double clicks, mobile networks). A
duplicate must never create a second order, capture twice, reserve stock twice or send two "order confirmed"
e-mails.

## Decision

Three layers, each solving a different problem:

1. **Producer idempotence** (`enable.idempotence=true`): prevents duplicates caused by the producer's own
   network retries. It does not help with outbox re-sends or consumer redelivery.
2. **Consumer deduplication** (`InboxGuard`): the consumer inserts `(consumer, eventId)` into
   `processed_event` with `ON CONFLICT DO NOTHING` **in the same transaction as its business update**. A
   duplicate finds the row and does nothing; a failed attempt rolls back both, so the retry runs again.
3. **Business idempotency** where the same intent can arrive as different messages or requests:
   - checkout: `Idempotency-Key` header, unique `(customer_id, idempotency_key)`, plus a hash of the request
     so a reused key with a different body is rejected;
   - inventory: one reservation per order (unique), conditional `UPDATE ... WHERE available >= n`, tombstones
     so a release that overtakes its reservation makes the late reservation a no-op;
   - payment: provider idempotency keys per operation, leases on in-flight payments, refunds bounded by the
     captured amount, webhook events deduplicated by provider event id;
   - notifications: unique `dedupe_key` per (template, recipient, source event).

## Consequences

- Replaying a dead-lettered message or rewinding a consumer group is safe by construction.
- `processed_event` grows; rows can be pruned after the topic retention period (not automated yet).
- Tests cover the duplicate paths explicitly (`repeatedAndConcurrentDuplicateReserveCommandsReserveOnce`,
  `concurrentDuplicateCapturesCaptureOnce`, `sameIdempotencyKeyNeverCreatesASecondOrder`, ...).
