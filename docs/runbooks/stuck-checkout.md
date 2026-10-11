# Checkout does not finish

A healthy checkout reaches CONFIRMED or CANCELLED within seconds (p95 about 1.1 s locally at 5 checkouts/s).

1. **Find the saga**: Admin > Orders > the order > "Checkout saga" shows the state, attempt, deadline and every
   transition with its trigger event. API: `GET /api/v1/admin/orders/<id>/saga`.
2. **Read the state**:
   - `INVENTORY_PENDING`, `PAYMENT_PENDING`, `INVENTORY_CONFIRM_PENDING`: waiting for a reply. Check that
     inventory-service or payment-service is up and its consumer lag (`kafka_consumer_fetch_manager_records_lag_max`).
     On deadline the scanner re-sends the command (idempotent) up to the attempt limit, then compensates.
   - `RECONCILIATION_REQUIRED`: see [payment-reconciliation.md](payment-reconciliation.md).
   - `COMPENSATING`: waiting for release / void / refund acknowledgements; the saga history shows which.
3. **Check the publishing side**: `outbox_pending` in order-service and the replying service; see
   [outbox-failed.md](outbox-failed.md).
4. **Check dead letters** for the order id: a reply that dead-lettered never reached the saga; see
   [dlt-replay.md](dlt-replay.md).
5. **Restarts are safe**: saga state is in PostgreSQL. Restarting order-service resumes at the persisted state
   (verified by SIGKILL during `PAYMENT_PENDING`).

Do not edit `checkout_saga` rows by hand except to move a deadline forward to trigger an immediate retry:

```sql
UPDATE checkout_saga SET deadline_at = now() WHERE order_id = '<id>' AND completed_at IS NULL;
```
