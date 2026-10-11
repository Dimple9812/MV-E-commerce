package com.marketflow.order.messaging;

import com.marketflow.order.application.CheckoutSagaOrchestrator;
import com.marketflow.order.persistence.CatalogProjection;
import com.marketflow.platform.kafka.EventListenerSupport;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class OrderConsumers {

    static final Set<String> INVENTORY_REPLIES = Set.of("InventoryReserved", "InventoryReservationFailed",
            "InventoryReservationReleased", "InventoryReservationConfirmed", "InventoryConfirmationFailed",
            "InventoryRestocked");
    static final Set<String> PAYMENT_REPLIES = Set.of("PaymentAuthorized", "PaymentFailed", "PaymentOutcomeUnknown",
            "PaymentCaptured", "PaymentCaptureFailed", "PaymentVoided", "RefundCompleted", "RefundFailed");

    private final EventListenerSupport support;
    private final CheckoutSagaOrchestrator orchestrator;
    private final CatalogProjection catalog;

    public OrderConsumers(EventListenerSupport support, CheckoutSagaOrchestrator orchestrator,
                          CatalogProjection catalog) {
        this.support = support;
        this.orchestrator = orchestrator;
        this.catalog = catalog;
    }

    public record Reply(UUID orderId) {
    }

    /** Saga replies are deduplicated by the inbox (same transaction as the saga update). */
    @KafkaListener(topics = "inventory.events", groupId = "order-service.saga-inventory")
    public void onInventory(String message) {
        support.handleOnce(message, "order-service.saga-inventory", INVENTORY_REPLIES, 1,
                e -> orchestrator.handle(e, support.payload(e, Reply.class).orderId()));
    }

    @KafkaListener(topics = "payment.events", groupId = "order-service.saga-payment")
    public void onPayment(String message) {
        support.handleOnce(message, "order-service.saga-payment", PAYMENT_REPLIES, 1,
                e -> orchestrator.handle(e, support.payload(e, Reply.class).orderId()));
    }

    public record Variant(UUID variantId, String sku, String name, BigDecimal price, boolean active) {
    }

    public record Product(UUID productId, UUID vendorId, String name, String status, String currency,
                          int maxPerOrder, String imageUrl, long productVersion, List<Variant> variants) {
    }

    @KafkaListener(topics = "catalog.product.events", groupId = "order-service.catalog")
    public void onProduct(String message) {
        support.handleOnce(message, "order-service.catalog", Set.of("ProductCreated", "ProductUpdated",
                "ProductPublished", "ProductPriceChanged", "ProductArchived", "ProductBlocked", "ProductDeleted"), 1,
                e -> {
                    Product p = support.payload(e, Product.class);
                    catalog.upsertProduct(p.productId(), p.vendorId(), p.name(), p.status(),
                            p.currency() == null ? "USD" : p.currency(), p.maxPerOrder() <= 0 ? 10 : p.maxPerOrder(),
                            p.imageUrl(), p.productVersion(), p.variants() == null ? List.of()
                                    : p.variants().stream().map(v -> new CatalogProjection.VariantSnapshot(
                                    v.variantId(), v.sku(), v.name(), v.price(), v.active())).toList());
                });
    }

    public record Vendor(UUID vendorId, UUID ownerUserId, String businessName, String status,
                         BigDecimal commissionRate, long vendorVersion) {
    }

    @KafkaListener(topics = "vendor.events", groupId = "order-service.vendors")
    public void onVendor(String message) {
        support.handleOnce(message, "order-service.vendors", Set.of("VendorApplicationSubmitted", "VendorApproved",
                "VendorRejected", "VendorSuspended", "VendorReinstated", "VendorProfileUpdated",
                "VendorCommissionChanged"), 1, e -> {
            Vendor v = support.payload(e, Vendor.class);
            catalog.upsertVendor(v.vendorId(), v.ownerUserId(), v.businessName(), v.status(),
                    v.commissionRate() == null ? new BigDecimal("0.1000") : v.commissionRate(), v.vendorVersion());
        });
    }
}
