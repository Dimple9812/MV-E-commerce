# Inspect and replay dead-lettered messages

## How messages get there

A consumer retries a failing message up to four times with exponential backoff (0.5 s, 1 s, 2 s, 4 s), then
publishes it to `<topic>.dlt` with headers describing the failure. Exceptions classified as non-retryable
(malformed JSON, unsupported `schemaVersion`, validation errors) skip the retries. Each consumer group
dead-letters independently, so one bad message on `vendor.events` can produce one DLT record per consuming
service, each tagged `x-failed-consumer`. notification-service uses retry topics
(`<topic>-notification-retry-N`) and `<topic>-notification-dlt`.

Every dead letter is recorded by analytics-service and shown on **Admin > Operations** and at
`GET /api/v1/admin/analytics/dead-letters`. Metric: `kafka_dlt_published_total{application=...}`; alert
`DeadLetters`.

## 1. Look

```bash
cd scripts && npm ci                                   # once: installs kafkajs
node dlt-replay.mjs list order.events.dlt              # partition@offset, eventType, eventId, consumer, cause
```

Headers on each record (also visible with `kafka-console-consumer.sh --property print.headers=true`):
`kafka_dlt-original-topic`, `kafka_dlt-original-partition`, `kafka_dlt-original-offset`,
`kafka_dlt-exception-cause-fqcn`, `kafka_dlt-exception-message`, `kafka_dlt-exception-stacktrace`,
`x-failed-consumer`.

Find the matching log line in the failing service (`docker compose logs order-service | grep <eventId>`), and
the correlation id in the event envelope to follow the whole request in Jaeger.

## 2. Decide

| Cause | Action |
|---|---|
| Bug in the consumer (NPE, unhandled case) | Fix, deploy, then replay. |
| Dependency was down (database, SMTP) and retries ran out | Confirm it is healthy, then replay. |
| `EventDeserializationException` / malformed JSON | The producer sent something invalid. Replay is pointless; fix the producer. Record the decision and leave or discard the message. |
| Unsupported `schemaVersion` | Deploy a consumer that understands the version, then replay. |
| Business rule violated (e.g. event for an unknown aggregate) | Usually a data problem. Investigate before replaying. |

## 3. Replay

Replay re-publishes the **original key and value to the original topic and partition**, so per-aggregate
order is kept. All consumer groups of that topic see it again; groups that already processed the event skip it
because consumers deduplicate on `eventId` in the same transaction as their update (ADR 0005). Replaying the
same record twice is therefore safe.

```bash
node dlt-replay.mjs replay order.events.dlt --offset 12                 # dry run: prints what would be sent
node dlt-replay.mjs replay order.events.dlt --offset 12 --execute
node dlt-replay.mjs replay order.events.dlt --consumer vendor-service --execute   # everything one consumer failed on
```

Several DLT records of the same message (one per failed consumer) are sent once. Replayed records carry
`x-replayed-from: <dlt-topic>:<partition>:<offset>`. `KAFKA_BROKERS` defaults to `localhost:9094` (the Compose
external listener).

Verify: the failing consumer's log shows the event processed, `processed_event` has the row, and no new DLT
record appears.

```bash
docker compose exec postgres psql -U postgres -d order_db \
  -c "select * from processed_event where event_id = '<eventId>'"
```

## 4. Clean up

DLT topics keep messages for 14 days (`retention.ms`). There is no in-place delete in Kafka; a handled
message simply stays until retention removes it. Note the outcome in the incident record.

## Verified

On the local stack (2026-10-11): a malformed `VendorApproved` was published to `vendor.events`; five consumer
groups (identity, catalog, inventory, order, analytics) dead-lettered it with
`EventDeserializationException`, analytics registered all five, and `list` showed them. A valid, already
processed event was then injected into `vendor.events.dlt` and replayed with `--execute`: every consumer
still had exactly one `processed_event` row for it, i.e. the redelivery was deduplicated.
