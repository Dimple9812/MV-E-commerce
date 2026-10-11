# ADR 0007: Prevent overselling with conditional updates and a stock ledger

Status: accepted (2026-10)

## Context

Popular items attract concurrent checkouts for the last unit. Read-then-write logic ("if available >= 1 then
decrement") races under concurrency and oversells.

## Decision

inventory-service changes stock only with a single conditional statement per line:

```sql
UPDATE inventory_item SET available = available - :qty, reserved = reserved + :qty, version = version + 1
 WHERE variant_id = :id AND available - :qty >= 0
RETURNING ...
```

If no row comes back the line is unavailable. A multi-line reservation runs in one transaction and is all or
nothing. Database `CHECK` constraints (`available >= 0`, `reserved >= 0`, `sold >= 0`) are a second line of
defence. Every change also writes a `stock_movement` row, an append-only ledger whose sums must equal the
counters (`GET /api/v1/admin/inventory/ledger-check`).

Reservations: unique per order, expire after `RESERVATION_TTL` (15 min) through a scanner, and are confirmed
into `sold` only after payment capture. A release that arrives before its reservation leaves a tombstone so
the late reservation does nothing.

The catalog's `available_qty` shown to shoppers is a projection for display only; it never decides a sale.

## Consequences

- `InventoryIT.concurrentCheckoutsCannotOversellTheLastUnit` fires 20 parallel reservations at one unit:
  exactly one succeeds. Under the 10 checkouts/s overload test the ledger check reported no mismatch.
- Hot SKUs serialise on one row lock. That is acceptable at marketplace scale; extreme flash sales would need
  sharded counters or pre-allocated tokens.
