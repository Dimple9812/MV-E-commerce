# Event contracts

Every Kafka message in MarketFlow is a JSON **envelope** ([`envelope.schema.json`](envelope.schema.json)) whose
`payload` is defined per event type in [`schemas/`](schemas). The schemas are generated from
[`generate.py`](generate.py), which is the single place to change a contract; the Java records in each service
are bindings of these contracts, not the contract itself. [`topics.json`](topics.json) is the machine-readable
catalogue.

```json
{
  "eventId": "3f0c…",              // unique; consumers deduplicate on it (processed_event table)
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "aggregateType": "Order",
  "aggregateId": "9b1e…",          // also the Kafka message key -> per-aggregate ordering
  "occurredAt": "2026-10-11T02:36:41.120Z",
  "correlationId": "c2d4…",        // follows one checkout through every service
  "causationId": "7aa1…",          // eventId of the message that triggered this one
  "producer": "order-service",
  "payload": { … }
}
```

## Checking contracts

```bash
python3 contracts/events/generate.py         # regenerate after editing generate.py
python3 scripts/validate-contracts.py        # validate the committed examples (CI does this)
python3 scripts/validate-contracts.py dump/*.jsonl   # validate events captured from a running stack
```

`examples/` holds one real event per observed type, captured from the local stack (demo data only). On
2026-10-11 the validator was run against every event on every topic of the local stack twice: 747 events
(33 types) from the long-running development data, and 500 events (36 types) after a clean install, seeding and
the end-to-end suites; 0 failures both times. The remaining 20 types are defined from the producer code but were
not emitted in those sessions (for example `ProductBlocked`, `VendorSuspended`, `RefundFailed`).

## Compatibility rules

1. **Additive changes keep the version.** A new *optional* payload field may be added to `schemaVersion` 1.
   Consumers ignore fields they do not know: payloads are bound with Spring Boot's `ObjectMapper`
   (`FAIL_ON_UNKNOWN_PROPERTIES=false`) and the envelope record is `@JsonIgnoreProperties(ignoreUnknown = true)`.
2. **Enumerations are open.** A status or reason string may gain values, so consumer code must treat an
   unknown value as "not for me" rather than fail. Likewise a consumer skips event types it does not handle:
   `EventListenerSupport` filters on `eventType` before any processing, so new event types on an existing
   topic never break old consumers.
3. **Breaking changes bump `schemaVersion`.** Removing or renaming a field, changing a type or meaning, or
   making an optional field required creates version 2. The producer publishes v2 only once every consumer
   accepts it; during migration it may publish both. Consumers declare the highest version they understand
   (`EventListenerSupport.handleOnce(..., maxVersion, ...)`) and send newer versions to the DLT instead of
   misreading them.
4. **Event types are never reused** for a different meaning; retire a type by stopping production.
5. **No sensitive data in events**: no passwords, tokens, card numbers or full addresses. `paymentToken` is a
   provider token, and e-mail addresses appear only where the consumer needs them to send mail.

## Topics

| Topic | Producer | Key | Partitions | Dead-letter topic |
|---|---|---|---|---|
| `identity.user.events` | identity-service | userId | 3 | `identity.user.events.dlt` |
| `vendor.events` | vendor-service | vendorId | 3 | `vendor.events.dlt` |
| `catalog.product.events` | catalog-service | productId | 3 | `catalog.product.events.dlt` |
| `inventory.commands` | order-service | orderId | 3 | `inventory.commands.dlt` |
| `inventory.events` | inventory-service | orderId (reservation events) or variantId (stock events) | 3 | `inventory.events.dlt` |
| `order.events` | order-service | orderId | 3 | `order.events.dlt` |
| `payment.commands` | order-service | orderId | 3 | `payment.commands.dlt` |
| `payment.events` | payment-service | orderId | 3 | `payment.events.dlt` |
| `review.events` | review-service | productId | 3 | `review.events.dlt` |
| `platform.audit.events` | every service | resourceId | 3 | `platform.audit.events.dlt` |

Partitions default to 3 (`marketflow.kafka.partitions`) and replication to 1 locally
(`marketflow.kafka.replication-factor`; use 3 with `min.insync.replicas=2` in production). Producers use
`acks=all` and idempotence; consumers read `read_committed` with manual (per-record) acknowledgement.
Retention is the broker default (7 days locally); dead-letter topics keep 14 days. Notification delivery uses
its own retry topics (`<topic>-notification-retry-N`) and `<topic>-notification-dlt`.

Consumer groups are named `<service>.<purpose>`, e.g. `order-service.saga-payment`,
`inventory-service.commands`, `analytics-service.orders`, so each service's projections progress
independently.

## Events

| Topic | Event | Producer | Consumers |
|---|---|---|---|
| `identity.user.events` | UserRegistered | identity-service | notification-service |
| `identity.user.events` | UserRolesChanged | identity-service | notification-service |
| `identity.user.events` | UserStatusChanged | identity-service | notification-service |
| `identity.user.events` | PasswordChanged | identity-service | notification-service |
| `vendor.events` | VendorApplicationSubmitted | vendor-service | identity-service, catalog-service, inventory-service, order-service, notification-service, analytics-service |
| `vendor.events` | VendorApproved | vendor-service | identity-service, catalog-service, inventory-service, order-service, notification-service, analytics-service |
| `vendor.events` | VendorRejected | vendor-service | identity-service, catalog-service, inventory-service, order-service, notification-service, analytics-service |
| `vendor.events` | VendorSuspended | vendor-service | identity-service, catalog-service, inventory-service, order-service, notification-service, analytics-service |
| `vendor.events` | VendorReinstated | vendor-service | identity-service, catalog-service, inventory-service, order-service, notification-service, analytics-service |
| `vendor.events` | VendorProfileUpdated | vendor-service | identity-service, catalog-service, inventory-service, order-service, notification-service, analytics-service |
| `vendor.events` | VendorCommissionChanged | vendor-service | identity-service, catalog-service, inventory-service, order-service, notification-service, analytics-service |
| `catalog.product.events` | ProductCreated | catalog-service | inventory-service, order-service |
| `catalog.product.events` | ProductUpdated | catalog-service | inventory-service, order-service |
| `catalog.product.events` | ProductPublished | catalog-service | inventory-service, order-service |
| `catalog.product.events` | ProductPriceChanged | catalog-service | inventory-service, order-service |
| `catalog.product.events` | ProductArchived | catalog-service | inventory-service, order-service |
| `catalog.product.events` | ProductBlocked | catalog-service | inventory-service, order-service |
| `catalog.product.events` | ProductDeleted | catalog-service | inventory-service, order-service |
| `inventory.commands` | InventoryReservationRequested | order-service | inventory-service |
| `inventory.commands` | InventoryConfirmationRequested | order-service | inventory-service |
| `inventory.commands` | InventoryReleaseRequested | order-service | inventory-service |
| `inventory.commands` | InventoryRestockRequested | order-service | inventory-service |
| `inventory.events` | InventoryReserved | inventory-service | order-service |
| `inventory.events` | InventoryReservationFailed | inventory-service | order-service |
| `inventory.events` | InventoryReservationConfirmed | inventory-service | order-service |
| `inventory.events` | InventoryReservationReleased | inventory-service | order-service |
| `inventory.events` | InventoryRestocked | inventory-service | order-service |
| `inventory.events` | StockLevelChanged | inventory-service | catalog-service, notification-service, analytics-service |
| `inventory.events` | LowStockDetected | inventory-service | notification-service, analytics-service |
| `order.events` | OrderCreated | order-service | vendor-service, notification-service, analytics-service, review-service |
| `order.events` | OrderConfirmed | order-service | vendor-service, notification-service, analytics-service, review-service |
| `order.events` | OrderCancelled | order-service | vendor-service, notification-service, analytics-service, review-service |
| `order.events` | OrderStatusChanged | order-service | notification-service, analytics-service |
| `order.events` | ShipmentCreated | order-service | notification-service, analytics-service |
| `order.events` | SuborderDelivered | order-service | review-service, analytics-service |
| `order.events` | OrderDelivered | order-service | notification-service, analytics-service |
| `order.events` | RefundRequested | order-service | notification-service, analytics-service |
| `payment.commands` | PaymentAuthorizationRequested | order-service | payment-service |
| `payment.commands` | PaymentCaptureRequested | order-service | payment-service |
| `payment.commands` | PaymentVoidRequested | order-service | payment-service |
| `payment.commands` | RefundRequested | order-service | payment-service |
| `payment.events` | PaymentAuthorized | payment-service | order-service, notification-service, analytics-service |
| `payment.events` | PaymentFailed | payment-service | order-service, notification-service, analytics-service |
| `payment.events` | PaymentOutcomeUnknown | payment-service | order-service, notification-service, analytics-service |
| `payment.events` | PaymentCaptured | payment-service | order-service, notification-service, analytics-service |
| `payment.events` | PaymentCaptureFailed | payment-service | order-service, notification-service, analytics-service |
| `payment.events` | PaymentVoided | payment-service | order-service, notification-service, analytics-service |
| `payment.events` | RefundCompleted | payment-service | order-service, notification-service, analytics-service |
| `payment.events` | RefundFailed | payment-service | order-service, notification-service, analytics-service |
| `review.events` | ReviewCreated | review-service | analytics-service |
| `review.events` | ReviewUpdated | review-service | analytics-service |
| `review.events` | ReviewDeleted | review-service | analytics-service |
| `review.events` | ReviewHidden | review-service | analytics-service |
| `review.events` | ReviewRestored | review-service | analytics-service |
| `review.events` | ProductRatingUpdated | review-service | catalog-service |
| `platform.audit.events` | AuditRecorded | every service | analytics-service |

The master brief's `NotificationRequested` is not a separate event: notification-service derives every
message from the domain events above, which avoids a second, drifting source of truth.
