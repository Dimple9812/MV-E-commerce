package com.marketflow.chat;

import com.marketflow.test.KafkaTestClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/** Feeds chat-service's projections through Kafka, exactly as the producing services would. */
final class ChatFixtures {

    private final String brokers;
    private final JdbcTemplate jdbc;

    ChatFixtures(String brokers, JdbcTemplate jdbc) {
        this.brokers = brokers;
        this.jdbc = jdbc;
    }

    record Shop(UUID vendorId, UUID ownerId, String name) {
    }

    record Sale(UUID orderId, UUID suborderId) {
    }

    Shop shop(String name, String status) {
        UUID vendorId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("vendor.events", vendorId.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                    "VendorApproved", "Vendor", vendorId.toString(), Map.of("vendorId", vendorId.toString(),
                            "ownerUserId", owner.toString(), "businessName", name,
                            "slug", name.toLowerCase().replace(' ', '-'), "status", status, "commissionRate", 0.1,
                            "contactEmail", "shop@example.test", "vendorVersion", 1)));
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from vendor_projection where vendor_id = ?", Integer.class, vendorId) == 1);
        return new Shop(vendorId, owner, name);
    }

    UUID product(Shop shop, String name) {
        UUID productId = UUID.randomUUID();
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("catalog.product.events", productId.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                    "ProductPublished", "Product", productId.toString(), Map.of("productId", productId.toString(),
                            "vendorId", shop.vendorId().toString(), "categoryId", UUID.randomUUID().toString(),
                            "name", name, "slug", name.toLowerCase().replace(' ', '-'), "status", "PUBLISHED",
                            "currency", "USD", "productVersion", 1,
                            "imageUrl", "https://img.example/" + productId + ".jpg",
                            "variants", List.of(
                                    Map.of("variantId", UUID.randomUUID().toString(), "sku", "A", "name", "Black",
                                            "price", 149, "active", true),
                                    Map.of("variantId", UUID.randomUUID().toString(), "sku", "B", "name", "Sand",
                                            "price", 139, "active", true)))));
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from product_projection where product_id = ?", Integer.class, productId) == 1);
        return productId;
    }

    Sale order(UUID customer, Shop shop, UUID productId, String eventType) {
        UUID orderId = UUID.randomUUID();
        UUID suborderId = UUID.randomUUID();
        orderEvent(UUID.randomUUID(), eventType, orderId, customer, shop, suborderId, productId, Map.of());
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from suborder_projection where suborder_id = ?", Integer.class, suborderId) == 1);
        return new Sale(orderId, suborderId);
    }

    void orderEvent(UUID eventId, String eventType, UUID orderId, UUID customer, Shop shop, UUID suborderId,
                    UUID productId, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.HashMap<>(Map.of("orderId", orderId.toString(),
                "customerId", customer.toString(), "status", "CONFIRMED", "currency", "USD", "totalAmount", 160.92,
                "suborders", List.of(Map.of("suborderId", suborderId.toString(), "vendorId", shop.vendorId().toString(),
                        "vendorName", shop.name(), "status", "CONFIRMED", "total", 160.92,
                        "items", List.of(Map.of("productId", productId.toString(), "productName", "Headphones",
                                "variantName", "Black", "quantity", 1, "unitPrice", 149))))));
        payload.putAll(extra);
        send("order.events", orderId, eventId, eventType, payload);
    }

    void shipment(UUID eventId, Sale sale, UUID customer, Shop shop) {
        send("order.events", sale.orderId(), eventId, "ShipmentCreated", Map.of("orderId", sale.orderId().toString(),
                "suborderId", sale.suborderId().toString(), "vendorId", shop.vendorId().toString(),
                "customerId", customer.toString(), "carrier", "DHL", "trackingNumber", "JD014600006281"));
    }

    private void send(String topic, UUID key, UUID eventId, String type, Map<String, Object> payload) {
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send(topic, key.toString(), KafkaTestClient.envelope(eventId, type, "Order", key.toString(), payload));
        }
    }
}
