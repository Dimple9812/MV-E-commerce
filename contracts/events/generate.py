#!/usr/bin/env python3
"""Source of truth for MarketFlow's Kafka event contracts.

Writes one JSON Schema (draft 2020-12) per event type into contracts/events/schemas/, the envelope schema, and
the topic catalogue (contracts/events/topics.json). The Java records in each service are bindings of these
contracts; scripts/validate-contracts.py checks recorded real events against them.

    python3 contracts/events/generate.py

Compatibility rules (see contracts/events/README.md): within a schemaVersion only optional fields may be added,
consumers ignore unknown fields, and string enumerations are open (consumers must tolerate unknown values).
"""
import json
import pathlib

HERE = pathlib.Path(__file__).parent
OUT = HERE / "schemas"
BASE = "https://marketflow.dev/contracts/events/"

UUID = {"type": "string", "format": "uuid"}
TS = {"type": "string", "format": "date-time"}
MONEY = {"type": "number", "minimum": 0, "description": "decimal amount in the order currency, 2 fraction digits"}
CURRENCY = {"type": "string", "pattern": "^[A-Z]{3}$"}
STR = {"type": "string"}
INT0 = {"type": "integer", "minimum": 0}


def obj(required, optional=None, desc=None):
    props = dict(required)
    props.update(optional or {})
    s = {"type": "object", "required": sorted(required), "properties": props}
    if desc:
        s["description"] = desc
    return s


def arr(item, min_items=0):
    return {"type": "array", "items": item, "minItems": min_items}


def nullable(s):
    return {"anyOf": [s, {"type": "null"}]}


def open_enum(*values):
    """Known values; consumers must handle values added later (forward compatibility)."""
    return {"type": "string", "examples": list(values), "description": "open enumeration: known values " + ", ".join(values)}


# ---------- shared payload shapes ----------
VENDOR = obj({"vendorId": UUID, "ownerUserId": UUID, "businessName": STR, "slug": STR,
              "status": open_enum("PENDING", "APPROVED", "REJECTED", "SUSPENDED"), "commissionRate": {"type": "number", "minimum": 0, "maximum": 0.5},
              "contactEmail": {"type": "string", "format": "email"}, "vendorVersion": INT0},
             {"reason": STR},
             "Full vendor snapshot; consumers keep projections and discard events whose vendorVersion is not newer.")

VARIANT = obj({"variantId": UUID, "sku": STR, "name": STR, "price": MONEY, "active": {"type": "boolean"}})
PRODUCT = obj({"productId": UUID, "vendorId": UUID, "categoryId": UUID, "name": STR, "slug": STR,
               "status": open_enum("DRAFT", "PUBLISHED", "ARCHIVED", "BLOCKED", "DELETED"), "currency": CURRENCY,
               "maxPerOrder": {"type": "integer", "minimum": 1}, "productVersion": INT0, "variants": arr(VARIANT)},
              {"imageUrl": STR,
               "priceChange": obj({"variantId": UUID, "sku": STR, "oldPrice": MONEY, "newPrice": MONEY},
                                  desc="present only on ProductPriceChanged")},
              "Full product snapshot; consumers apply it only when productVersion is newer than what they hold.")

INV_LINE = obj({"variantId": UUID, "sku": STR, "quantity": {"type": "integer", "minimum": 1}})
INV_COMMAND = obj({"orderId": UUID, "lines": arr(INV_LINE, 1)}, {"reason": STR})
RES_OUTCOME = obj({"orderId": UUID, "reservationId": UUID, "status": open_enum("CONFIRMED", "RELEASED", "EXPIRED", "RETURNED")}, {"reason": STR})

ITEM = obj({"productId": UUID, "variantId": UUID, "sku": STR, "productName": STR, "variantName": STR,
            "quantity": {"type": "integer", "minimum": 1}, "unitPrice": MONEY, "lineTotal": MONEY})
SUBORDER = obj({"suborderId": UUID, "vendorId": UUID, "vendorName": STR, "status": STR, "itemsSubtotal": MONEY,
                "discountAmount": MONEY, "shippingAmount": MONEY, "taxAmount": MONEY, "total": MONEY,
                "commissionRate": {"type": "number"}, "commissionAmount": MONEY, "vendorNetAmount": MONEY,
                "items": arr(ITEM, 1)})
ORDER = obj({"orderId": UUID, "customerId": UUID, "status": STR, "currency": CURRENCY, "itemsSubtotal": MONEY,
             "discountTotal": MONEY, "shippingTotal": MONEY, "taxTotal": MONEY, "totalAmount": MONEY,
             "wasConfirmed": {"type": "boolean", "description": "true on OrderCancelled when the order had been confirmed (a refund follows)"},
             "suborders": arr(SUBORDER, 1)},
            {"reason": open_enum("OUT_OF_STOCK", "PAYMENT_DECLINED", "CAPTURE_FAILED", "STOCK_UNAVAILABLE_AFTER_PAYMENT",
                                 "INVENTORY_TIMEOUT", "CUSTOMER_REQUEST")},
            "Order snapshot including vendor suborders and the authoritative, server-calculated amounts.")
ORDER_STATUS = obj({"orderId": UUID, "customerId": UUID, "status": STR}, {"reason": STR})

PAYMENT = obj({"orderId": UUID, "paymentId": UUID, "customerId": UUID, "amount": MONEY, "currency": CURRENCY, "status": STR},
              {"code": {"type": "string", "description": "provider result code, e.g. card_declined, timeout"},
               "refundId": UUID, "refundedAmount": MONEY})
PAYMENT_COMMAND = obj({"orderId": UUID, "amount": MONEY, "currency": CURRENCY}, {"reason": STR})

REVIEW = obj({"reviewId": UUID, "productId": UUID, "customerId": UUID, "rating": {"type": "integer", "minimum": 1, "maximum": 5},
              "status": open_enum("PUBLISHED", "HIDDEN", "DELETED")})

# ---------- topics and events ----------
# Each event: (type, payload schema, consumers, description)
TOPICS = {
    "identity.user.events": {
        "producer": "identity-service", "key": "userId", "aggregate": "User",
        "events": [
            ("UserRegistered", obj({"userId": UUID, "email": {"type": "string", "format": "email"}, "fullName": STR, "registeredAt": TS}),
             ["notification-service"], "A customer account was created."),
            ("UserRolesChanged", obj({"userId": UUID, "roles": arr(open_enum("CUSTOMER", "VENDOR", "ADMIN"), 1)}, {"reason": STR}),
             ["notification-service"], "Roles changed, e.g. VENDOR granted after approval."),
            ("UserStatusChanged", obj({"userId": UUID, "status": open_enum("ACTIVE", "DEACTIVATED")}),
             ["notification-service"], "An administrator activated or deactivated the account."),
            ("PasswordChanged", obj({"userId": UUID, "changedAt": TS}),
             ["notification-service"], "Password changed or reset; all refresh tokens were revoked."),
        ]},
    "vendor.events": {
        "producer": "vendor-service", "key": "vendorId", "aggregate": "Vendor",
        "events": [(t, VENDOR, ["identity-service", "catalog-service", "inventory-service", "order-service", "notification-service", "analytics-service"], d) for t, d in [
            ("VendorApplicationSubmitted", "A customer applied to sell."),
            ("VendorApproved", "Application approved; identity grants the VENDOR role."),
            ("VendorRejected", "Application rejected with a reason."),
            ("VendorSuspended", "Seller suspended; catalog hides their products, checkout rejects them."),
            ("VendorReinstated", "Suspension lifted."),
            ("VendorProfileUpdated", "Shop name, description or contact details changed."),
            ("VendorCommissionChanged", "Commission rate changed by an administrator."),
        ]]},
    "catalog.product.events": {
        "producer": "catalog-service", "key": "productId", "aggregate": "Product",
        "events": [(t, PRODUCT, ["inventory-service", "order-service"], d) for t, d in [
            ("ProductCreated", "Draft created with its variants."),
            ("ProductUpdated", "Details, variants or images changed."),
            ("ProductPublished", "Visible in the storefront and purchasable."),
            ("ProductPriceChanged", "A variant price changed; priceChange carries old and new price."),
            ("ProductArchived", "Withdrawn by the seller."),
            ("ProductBlocked", "Blocked by moderation."),
            ("ProductDeleted", "Never-published draft deleted (status DELETED)."),
        ]]},
    "inventory.commands": {
        "producer": "order-service", "key": "orderId", "aggregate": "Order",
        "events": [(t, INV_COMMAND, ["inventory-service"], d) for t, d in [
            ("InventoryReservationRequested", "Saga step: reserve every line or none."),
            ("InventoryConfirmationRequested", "Saga step after capture: turn the reservation into a sale."),
            ("InventoryReleaseRequested", "Compensation: release a reservation (idempotent; tombstones a late reservation)."),
            ("InventoryRestockRequested", "Compensation after a confirmed sale is cancelled: return sold units."),
        ]]},
    "inventory.events": {
        "producer": "inventory-service", "key": "orderId (reservation events) or variantId (stock events)", "aggregate": "Reservation | InventoryItem",
        "events": [
            ("InventoryReserved", obj({"orderId": UUID, "reservationId": UUID, "expiresAt": TS, "lines": arr(INV_LINE, 1)}),
             ["order-service"], "All lines reserved until expiresAt."),
            ("InventoryReservationFailed", obj({"orderId": UUID, "reason": STR,
                                                "unavailable": arr(obj({"variantId": UUID, "sku": STR, "requested": {"type": "integer"}, "available": {"type": "integer"}}))}),
             ["order-service"], "Not enough stock for at least one line; nothing was reserved."),
            ("InventoryReservationConfirmed", RES_OUTCOME, ["order-service"], "Reserved units became sold."),
            ("InventoryReservationReleased", RES_OUTCOME, ["order-service"], "Reserved units returned to available."),
            ("InventoryRestocked", RES_OUTCOME, ["order-service"], "Sold units returned after a cancellation."),
            ("StockLevelChanged", obj({"variantId": UUID, "productId": UUID, "vendorId": UUID, "sku": STR, "available": INT0,
                                       "reserved": INT0, "sold": INT0, "stockVersion": INT0}),
             ["catalog-service", "notification-service", "analytics-service"], "Availability changed; stockVersion orders updates per variant."),
            ("LowStockDetected", obj({"variantId": UUID, "productId": UUID, "vendorId": UUID, "sku": STR, "productName": STR,
                                      "available": INT0, "threshold": INT0}),
             ["notification-service", "analytics-service"], "Available stock crossed the seller's threshold downwards."),
        ]},
    "order.events": {
        "producer": "order-service", "key": "orderId", "aggregate": "Order",
        "events": [
            ("OrderCreated", ORDER, ["vendor-service", "notification-service", "analytics-service", "review-service"], "Pending order and suborders created; the saga starts."),
            ("OrderConfirmed", ORDER, ["vendor-service", "notification-service", "analytics-service", "review-service"], "Payment captured and stock confirmed."),
            ("OrderCancelled", ORDER, ["vendor-service", "notification-service", "analytics-service", "review-service"], "Cancelled by compensation or by the customer."),
            ("OrderStatusChanged", ORDER_STATUS, ["notification-service", "analytics-service"], "Other status changes (FAILED, REFUND_FAILED)."),
            ("ShipmentCreated", obj({"orderId": UUID, "suborderId": UUID, "vendorId": UUID, "customerId": UUID, "carrier": STR, "trackingNumber": STR}),
             ["notification-service", "analytics-service"], "A seller shipped their suborder."),
            ("SuborderDelivered", obj({"orderId": UUID, "suborderId": UUID, "vendorId": UUID, "customerId": UUID, "productIds": arr(UUID, 1)}),
             ["review-service", "analytics-service"], "A suborder was delivered; its products become reviewable."),
            ("OrderDelivered", ORDER_STATUS, ["notification-service", "analytics-service"], "Every suborder delivered."),
            ("RefundRequested", obj({"orderId": UUID, "customerId": UUID, "amount": MONEY, "reason": STR}),
             ["notification-service", "analytics-service"], "Informational: a refund was requested for a cancelled confirmed order."),
        ]},
    "payment.commands": {
        "producer": "order-service", "key": "orderId", "aggregate": "Order",
        "events": [
            ("PaymentAuthorizationRequested", obj({"orderId": UUID, "customerId": UUID, "amount": MONEY, "currency": CURRENCY,
                                                   "paymentToken": {"type": "string", "description": "sandbox/provider token, never card data"}}),
             ["payment-service"], "Saga step: authorise the order total."),
            ("PaymentCaptureRequested", PAYMENT_COMMAND, ["payment-service"], "Saga step after reservation: capture the authorisation."),
            ("PaymentVoidRequested", PAYMENT_COMMAND, ["payment-service"], "Compensation: release an uncaptured authorisation."),
            ("RefundRequested", PAYMENT_COMMAND, ["payment-service"], "Compensation or customer cancellation: refund a captured payment."),
        ]},
    "payment.events": {
        "producer": "payment-service", "key": "orderId", "aggregate": "Payment",
        "events": [(t, PAYMENT, ["order-service", "notification-service", "analytics-service"], d) for t, d in [
            ("PaymentAuthorized", "Funds held."),
            ("PaymentFailed", "Authorisation declined; code says why."),
            ("PaymentOutcomeUnknown", "Provider did not answer in time; reconciliation will settle the outcome."),
            ("PaymentCaptured", "Funds captured."),
            ("PaymentCaptureFailed", "Capture failed; the authorisation is voided."),
            ("PaymentVoided", "Authorisation released."),
            ("RefundCompleted", "Refund succeeded; refundId and refundedAmount set."),
            ("RefundFailed", "Refund failed at the provider; needs manual follow-up."),
        ]]},
    "review.events": {
        "producer": "review-service", "key": "productId", "aggregate": "Review | ProductRating",
        "events": [(t, REVIEW, ["analytics-service"], d) for t, d in [
            ("ReviewCreated", "Verified purchaser posted a review."),
            ("ReviewUpdated", "Author edited a review."),
            ("ReviewDeleted", "Author deleted a review."),
            ("ReviewHidden", "Moderator hid a review."),
            ("ReviewRestored", "Moderator restored a review."),
        ]] + [("ProductRatingUpdated", obj({"productId": UUID, "averageRating": {"type": "number", "minimum": 0, "maximum": 5}, "reviewCount": INT0}),
               ["catalog-service"], "Recomputed aggregate rating of a product.")]},
    "platform.audit.events": {
        "producer": "every service", "key": "resourceId", "aggregate": "any",
        "events": [("AuditRecorded", obj({"service": STR, "action": STR, "resourceType": STR, "resourceId": STR,
                                          "outcome": open_enum("SUCCESS", "FAILURE")},
                                         {"actorId": nullable(UUID), "actorRole": nullable(STR),
                                          "details": {"type": "object", "description": "non-sensitive context; never passwords, tokens or card data"}}),
                     ["analytics-service"], "Security-relevant or administrative action.")]},
}

ENVELOPE = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": BASE + "envelope.schema.json",
    "title": "MarketFlow event envelope",
    "description": "Every Kafka message value is one envelope serialized as UTF-8 JSON. The message key is the aggregate id.",
    "type": "object",
    "required": ["eventId", "eventType", "schemaVersion", "aggregateType", "aggregateId", "occurredAt", "producer", "payload"],
    "properties": {
        "eventId": dict(UUID, description="unique per event; consumers deduplicate on it"),
        "eventType": {"type": "string", "pattern": "^[A-Z][A-Za-z]+$"},
        "schemaVersion": {"type": "integer", "minimum": 1, "description": "major version of the payload schema"},
        "aggregateType": STR,
        "aggregateId": STR,
        "occurredAt": TS,
        "correlationId": dict(nullable(STR), description="request/checkout correlation id propagated across services"),
        "causationId": dict(nullable(STR), description="eventId of the message that caused this one, if any"),
        "producer": STR,
        "payload": {"type": "object"},
    },
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (HERE / "envelope.schema.json").write_text(json.dumps(ENVELOPE, indent=2) + "\n")
    catalogue = {"topics": {}}
    count = 0
    for topic, t in TOPICS.items():
        entries = []
        for etype, payload, consumers, desc in t["events"]:
            name = f"{topic}.{etype}.v1.schema.json"
            schema = {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "$id": BASE + "schemas/" + name,
                "title": f"{etype} v1",
                "description": desc,
                "allOf": [{"$ref": "../envelope.schema.json"}],
                "properties": {"eventType": {"const": etype}, "schemaVersion": {"const": 1}, "payload": payload},
                "x-topic": topic, "x-producer": t["producer"], "x-consumers": consumers, "x-key": t["key"],
            }
            (OUT / name).write_text(json.dumps(schema, indent=2) + "\n")
            entries.append({"eventType": etype, "schemaVersion": 1, "schema": f"schemas/{name}", "consumers": consumers, "description": desc})
            count += 1
        catalogue["topics"][topic] = {"producer": t["producer"], "key": t["key"], "aggregate": t["aggregate"],
                                      "partitions": 3, "deadLetterTopic": topic + ".dlt", "events": entries}
    (HERE / "topics.json").write_text(json.dumps(catalogue, indent=2) + "\n")
    print(f"wrote envelope, {count} event schemas, topics.json")


if __name__ == "__main__":
    main()
