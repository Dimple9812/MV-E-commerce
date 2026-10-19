package com.marketflow.chat.messaging;

import com.marketflow.chat.persistence.ProjectionRepository;
import com.marketflow.platform.kafka.EventListenerSupport;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps chat's copies of vendors (who owns which shop, is it active) and products (cards, context headers)
 * current. Both events carry full snapshots with a version, so applying them is idempotent and order-safe.
 */
@Component
public class ProjectionsConsumer {

    static final String VENDORS = "chat-service.vendors";
    static final String PRODUCTS = "chat-service.products";

    private final EventListenerSupport support;
    private final ProjectionRepository projections;

    public ProjectionsConsumer(EventListenerSupport support, ProjectionRepository projections) {
        this.support = support;
        this.projections = projections;
    }

    public record VendorPayload(UUID vendorId, UUID ownerUserId, String businessName, String slug, String status,
                                long vendorVersion) {
    }

    public record Variant(UUID variantId, BigDecimal price, Boolean active) {
    }

    public record ProductPayload(UUID productId, UUID vendorId, String name, String slug, String status,
                                 String currency, String imageUrl, long productVersion, List<Variant> variants) {
    }

    @KafkaListener(topics = "vendor.events", groupId = VENDORS)
    public void onVendor(String message) {
        support.handleOnce(message, VENDORS, Set.of("VendorApplicationSubmitted", "VendorApproved", "VendorRejected",
                "VendorSuspended", "VendorReinstated", "VendorProfileUpdated", "VendorCommissionChanged"), 1, e -> {
            VendorPayload p = support.payload(e, VendorPayload.class);
            projections.upsertVendor(p.vendorId(), p.ownerUserId(), p.businessName(), p.slug(), p.status(),
                    p.vendorVersion());
        });
    }

    @KafkaListener(topics = "catalog.product.events", groupId = PRODUCTS)
    public void onProduct(String message) {
        support.handleOnce(message, PRODUCTS, Set.of("ProductCreated", "ProductUpdated", "ProductPublished",
                "ProductPriceChanged", "ProductArchived", "ProductBlocked", "ProductDeleted"), 1, e -> {
            ProductPayload p = support.payload(e, ProductPayload.class);
            BigDecimal minPrice = p.variants() == null ? null : p.variants().stream()
                    .filter(v -> v.active() == null || v.active()).map(Variant::price).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            projections.upsertProduct(p.productId(), p.vendorId(), p.name(), p.slug(), p.imageUrl(), minPrice,
                    p.currency() == null ? "USD" : p.currency(), p.status(), p.productVersion());
        });
    }
}
