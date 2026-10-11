# Outbox events not publishing

## Signals

- `OutboxBacklog`: `outbox_pending > 100` for 5 min in a service: the publisher is slow or Kafka is unreachable.
- `OutboxEventsParked`: `outbox_failed > 0`: an event exhausted its publish retries and is parked as `FAILED`.
- Business symptom: orders stay pending, notifications or seller earnings do not appear.

## Diagnose

```bash
docker compose logs --since 15m order-service | grep -i outbox
docker compose exec postgres psql -U postgres -d order_db -c \
  "select id, event_type, topic, attempts, last_error, created_at from outbox_event where status <> 'PUBLISHED' order by id limit 20"
```

`last_error` holds the producer exception (broker unreachable, record too large, unknown topic). Check Kafka:
`docker compose ps kafka`, `kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic <topic>`.

## Fix and requeue

1. Fix the cause (start Kafka, create the missing topic, raise `max.request.size` if a payload is too big).
2. Pending rows retry on their own. Parked rows need an explicit requeue, which is safe because consumers
   deduplicate on `eventId`:

```sql
-- in the affected service's database; requeue in id order to keep per-aggregate ordering
UPDATE outbox_event SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL
 WHERE status = 'FAILED';
```

3. Watch `outbox_pending` fall to 0 and `outbox_published_total` rise.

Never delete outbox rows to "unblock" a service: the event would be lost and the downstream state would
diverge permanently.
