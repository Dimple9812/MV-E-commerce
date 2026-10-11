package com.marketflow.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketflow.platform.events.EventEnvelope;
import com.marketflow.platform.kafka.EventListenerSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;

/**
 * Projects domain events into reporting tables. Each listener uses the inbox (exactly-once effect per event) and
 * idempotent upserts, so replays and redeliveries never double-count GMV or units.
 */
@Component
public class ReadModelConsumers {

    private static final Logger log = LoggerFactory.getLogger(ReadModelConsumers.class);

    private final EventListenerSupport support;
    private final JdbcTemplate jdbc;

    public ReadModelConsumers(EventListenerSupport support, JdbcTemplate jdbc) {
        this.support = support;
        this.jdbc = jdbc;
    }

    private void checkpoint(String stream, EventEnvelope e) {
        jdbc.update("""
                INSERT INTO consumer_checkpoint (stream, last_event_at, events_total) VALUES (?, ?, 1)
                ON CONFLICT (stream) DO UPDATE SET last_event_at = GREATEST(consumer_checkpoint.last_event_at,
                    EXCLUDED.last_event_at), events_total = consumer_checkpoint.events_total + 1
                """, stream, Timestamp.from(e.occurredAt()));
    }

    // ------------------------------------------------------------------ orders

    @KafkaListener(topics = "order.events", groupId = "analytics-service.orders")
    public void onOrder(String message) {
        support.handleOnce(message, "analytics-service.orders", Set.of("OrderCreated", "OrderConfirmed",
                "OrderCancelled", "OrderStatusChanged", "ShipmentCreated", "OrderDelivered"), 1, e -> {
            JsonNode p = e.payload();
            UUID order = UUID.fromString(p.path("orderId").asText());
            Timestamp at = Timestamp.from(e.occurredAt());
            switch (e.eventType()) {
                case "OrderCreated" -> {
                    int items = 0;
                    for (JsonNode s : p.path("suborders")) {
                        for (JsonNode i : s.path("items")) {
                            items += i.path("quantity").asInt();
                        }
                    }
                    jdbc.update("""
                            INSERT INTO order_fact (order_id, customer_id, status, total, currency, vendor_count,
                                                    item_count, created_at)
                            VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?) ON CONFLICT (order_id) DO NOTHING
                            """, order, UUID.fromString(p.path("customerId").asText()), p.path("totalAmount").decimalValue(),
                            p.path("currency").asText("USD"), p.path("suborders").size(), items, at);
                }
                case "OrderConfirmed" -> {
                    upsertOrder(p, order, "CONFIRMED", at);
                    jdbc.update("UPDATE order_fact SET status = 'CONFIRMED', confirmed_at = ? WHERE order_id = ?", at,
                            order);
                    for (JsonNode s : p.path("suborders")) {
                        UUID sub = UUID.fromString(s.path("suborderId").asText());
                        UUID vendor = UUID.fromString(s.path("vendorId").asText());
                        jdbc.update("""
                                INSERT INTO suborder_fact (suborder_id, order_id, vendor_id, vendor_name, status, total,
                                                           commission, vendor_net, confirmed_at)
                                VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?) ON CONFLICT (suborder_id) DO NOTHING
                                """, sub, order, vendor, s.path("vendorName").asText(""), dec(s, "total"),
                                dec(s, "commissionAmount"), dec(s, "vendorNetAmount"), at);
                        for (JsonNode i : s.path("items")) {
                            jdbc.update("""
                                    INSERT INTO item_fact (suborder_id, variant_id, product_id, vendor_id, product_name,
                                                           sku, quantity, revenue, confirmed_at)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                                    """, sub, UUID.fromString(i.path("variantId").asText()),
                                    UUID.fromString(i.path("productId").asText()), vendor,
                                    i.path("productName").asText(""), i.path("sku").asText(""),
                                    i.path("quantity").asInt(), dec(i, "lineTotal"), at);
                        }
                    }
                }
                case "OrderCancelled" -> {
                    upsertOrder(p, order, "CANCELLED", at);
                    jdbc.update("UPDATE order_fact SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = ? "
                            + "WHERE order_id = ?", at, p.path("reason").asText(null), order);
                    if (p.path("wasConfirmed").asBoolean()) {
                        jdbc.update("UPDATE suborder_fact SET status = 'CANCELLED' WHERE order_id = ?", order);
                        jdbc.update("UPDATE item_fact SET cancelled = true WHERE suborder_id IN "
                                + "(SELECT suborder_id FROM suborder_fact WHERE order_id = ?)", order);
                    }
                }
                case "OrderStatusChanged", "OrderDelivered" -> jdbc.update(
                        "UPDATE order_fact SET status = ? WHERE order_id = ?", p.path("status").asText(), order);
                case "ShipmentCreated" -> jdbc.update("UPDATE suborder_fact SET status = 'SHIPPED' WHERE suborder_id = ?",
                        UUID.fromString(p.path("suborderId").asText()));
                default -> {
                }
            }
            checkpoint("orders", e);
        });
    }

    /** OrderCreated may be missed if analytics started late; confirmations/cancellations create the row too. */
    private void upsertOrder(JsonNode p, UUID order, String status, Timestamp at) {
        if (!p.has("customerId") || !p.has("totalAmount")) {
            return;
        }
        jdbc.update("""
                INSERT INTO order_fact (order_id, customer_id, status, total, currency, vendor_count, item_count,
                                        created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?) ON CONFLICT (order_id) DO NOTHING
                """, order, UUID.fromString(p.path("customerId").asText()), status, p.path("totalAmount").decimalValue(),
                p.path("currency").asText("USD"), p.path("suborders").size(), at);
    }

    // ------------------------------------------------------------------ payments

    @KafkaListener(topics = "payment.events", groupId = "analytics-service.payments")
    public void onPayment(String message) {
        support.handleOnce(message, "analytics-service.payments", Set.of("PaymentAuthorized", "PaymentFailed",
                "PaymentCaptured", "PaymentCaptureFailed", "PaymentVoided", "RefundCompleted", "PaymentOutcomeUnknown"),
                1, e -> {
                    JsonNode p = e.payload();
                    UUID order = UUID.fromString(p.path("orderId").asText());
                    String status = switch (e.eventType()) {
                        case "PaymentAuthorized" -> "AUTHORIZED";
                        case "PaymentFailed" -> "DECLINED";
                        case "PaymentCaptured" -> "CAPTURED";
                        case "PaymentCaptureFailed" -> "CAPTURE_FAILED";
                        case "PaymentVoided" -> "VOIDED";
                        case "RefundCompleted" -> "REFUNDED";
                        default -> "UNKNOWN";
                    };
                    BigDecimal refunded = e.eventType().equals("RefundCompleted") ? dec(p, "refundedAmount") : BigDecimal.ZERO;
                    jdbc.update("""
                            INSERT INTO payment_fact (order_id, status, amount, refunded_amount, failure_code, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT (order_id) DO UPDATE SET status = EXCLUDED.status,
                                refunded_amount = payment_fact.refunded_amount + EXCLUDED.refunded_amount,
                                failure_code = coalesce(EXCLUDED.failure_code, payment_fact.failure_code),
                                updated_at = EXCLUDED.updated_at
                            WHERE payment_fact.updated_at <= EXCLUDED.updated_at OR EXCLUDED.refunded_amount > 0
                            """, order, status, dec(p, "amount"), refunded,
                            e.eventType().equals("PaymentFailed") ? p.path("code").asText(null) : null,
                            Timestamp.from(e.occurredAt()));
                    checkpoint("payments", e);
                });
    }

    // ------------------------------------------------------------------ inventory

    @KafkaListener(topics = "inventory.events", groupId = "analytics-service.inventory")
    public void onInventory(String message) {
        support.handleOnce(message, "analytics-service.inventory", Set.of("LowStockDetected", "StockLevelChanged"), 1,
                e -> {
                    JsonNode p = e.payload();
                    UUID variant = UUID.fromString(p.path("variantId").asText());
                    if (e.eventType().equals("LowStockDetected")) {
                        jdbc.update("""
                                INSERT INTO inventory_alert (variant_id, vendor_id, product_id, sku, product_name,
                                                             available, threshold, detected_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT (variant_id) DO UPDATE SET available = EXCLUDED.available,
                                    threshold = EXCLUDED.threshold, detected_at = EXCLUDED.detected_at, resolved_at = NULL
                                """, variant, UUID.fromString(p.path("vendorId").asText()),
                                UUID.fromString(p.path("productId").asText()), p.path("sku").asText(),
                                p.path("productName").asText(null), p.path("available").asInt(),
                                p.path("threshold").asInt(), Timestamp.from(e.occurredAt()));
                    } else {
                        jdbc.update("UPDATE inventory_alert SET available = ?, resolved_at = CASE WHEN ? > threshold "
                                + "THEN now() ELSE NULL END WHERE variant_id = ?", p.path("available").asInt(),
                                p.path("available").asInt(), variant);
                    }
                    checkpoint("inventory", e);
                });
    }

    // ------------------------------------------------------------------ vendors

    @KafkaListener(topics = "vendor.events", groupId = "analytics-service.vendors")
    public void onVendor(String message) {
        support.handleOnce(message, "analytics-service.vendors", Set.of("VendorApplicationSubmitted", "VendorApproved",
                "VendorRejected", "VendorSuspended", "VendorReinstated", "VendorProfileUpdated",
                "VendorCommissionChanged"), 1, e -> {
            JsonNode p = e.payload();
            jdbc.update("""
                    INSERT INTO vendor_dim (vendor_id, owner_user_id, business_name, status, vendor_version)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (vendor_id) DO UPDATE SET business_name = EXCLUDED.business_name,
                        status = EXCLUDED.status, vendor_version = EXCLUDED.vendor_version
                    WHERE vendor_dim.vendor_version < EXCLUDED.vendor_version
                    """, UUID.fromString(p.path("vendorId").asText()), UUID.fromString(p.path("ownerUserId").asText()),
                    p.path("businessName").asText(), p.path("status").asText(), p.path("vendorVersion").asLong());
        });
    }

    // ------------------------------------------------------------------ audit

    @KafkaListener(topics = "platform.audit.events", groupId = "analytics-service.audit")
    public void onAudit(String message) {
        support.handle(message, e -> {
            if (!"AuditRecorded".equals(e.eventType())) {
                return;
            }
            JsonNode p = e.payload();
            String actor = p.path("actorId").asText(null);
            // idempotent by event ID (primary key)
            jdbc.update("""
                    INSERT INTO audit_log (event_id, service, actor_id, actor_role, action, resource_type, resource_id,
                                           outcome, details, correlation_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT (event_id) DO NOTHING
                    """, e.eventId(), p.path("service").asText(e.producer()),
                    actor == null || actor.isBlank() ? null : UUID.fromString(actor), p.path("actorRole").asText(null),
                    p.path("action").asText(), p.path("resourceType").asText(), p.path("resourceId").asText(),
                    p.path("outcome").asText("SUCCESS"), p.path("details").toString(), e.correlationId(),
                    Timestamp.from(e.occurredAt()));
        });
    }

    // ------------------------------------------------------------------ dead letters (all services)

    @KafkaListener(topicPattern = ".*(\\.dlt|-notification-dlt)", groupId = "analytics-service.dlt-monitor",
            properties = "metadata.max.age.ms=30000")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        // never throw from here: a failure would itself be dead-lettered into a topic this listener consumes
        try {
            recordDeadLetter(record);
        } catch (RuntimeException e) {
            log.error("Could not record dead letter topic={} offset={}: {}", record.topic(), record.offset(),
                    e.getMessage());
        }
    }

    private void recordDeadLetter(ConsumerRecord<String, String> record) {
        String eventType = null;
        String eventId = null;
        try {
            var e = support.codec().parse(record.value());
            eventType = e.eventType();
            eventId = e.eventId().toString();
        } catch (RuntimeException ignored) {
            // malformed payloads are exactly the kind of thing that ends up here
        }
        jdbc.update("""
                INSERT INTO dead_letter (topic, partition_no, offset_no, consumer, exception, event_type, event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, record.topic(), record.partition(), record.offset(), consumerOf(record),
                truncate(rootCause(record)), eventType, eventId);
        log.warn("Dead letter observed topic={} offset={} type={}", record.topic(), record.offset(), eventType);
    }

    /** Our error handler tags the failing service; Spring's retry topics only carry the consumer group. */
    private static String consumerOf(ConsumerRecord<String, String> r) {
        String c = header(r, "x-failed-consumer");
        return c != null ? c : header(r, "kafka_dlt-original-consumer-group");
    }

    /**
     * The listener wraps every failure in ListenerExecutionFailedException; the cause class is what an operator
     * needs. Only class names are stored: exception messages can quote payload content (e-mail addresses etc.).
     */
    private static String rootCause(ConsumerRecord<String, String> r) {
        String cause = header(r, "kafka_dlt-exception-cause-fqcn");
        return cause != null ? cause : header(r, "kafka_dlt-exception-fqcn");
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 290));
    }

    private static BigDecimal dec(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asText());
    }
}
