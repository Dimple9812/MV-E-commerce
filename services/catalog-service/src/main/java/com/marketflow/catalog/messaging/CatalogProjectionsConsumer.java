package com.marketflow.catalog.messaging;

import com.marketflow.catalog.cache.CacheAside;
import com.marketflow.catalog.cache.CacheKeys;
import com.marketflow.catalog.domain.VendorProjection;
import com.marketflow.catalog.persistence.ProductRepository;
import com.marketflow.catalog.persistence.VendorProjectionRepository;
import com.marketflow.platform.kafka.EventListenerSupport;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps catalog's local read models current:
 * vendor status (ownership + storefront visibility), stock availability and rating aggregates.
 */
@Component
public class CatalogProjectionsConsumer {

    private final EventListenerSupport support;
    private final VendorProjectionRepository vendors;
    private final ProductRepository products;
    private final CacheAside cache;

    public CatalogProjectionsConsumer(EventListenerSupport support, VendorProjectionRepository vendors,
                                      ProductRepository products, CacheAside cache) {
        this.support = support;
        this.vendors = vendors;
        this.products = products;
        this.cache = cache;
    }

    public record VendorPayload(UUID vendorId, UUID ownerUserId, String businessName, String slug, String status,
                                long vendorVersion) {
    }

    public record StockPayload(UUID variantId, UUID productId, int available, long stockVersion) {
    }

    public record RatingPayload(UUID productId, BigDecimal averageRating, int reviewCount) {
    }

    @KafkaListener(topics = "vendor.events", groupId = "catalog-service.vendors")
    public void onVendor(String message) {
        support.handleOnce(message, "catalog-service.vendors", Set.of("VendorApplicationSubmitted", "VendorApproved",
                "VendorRejected", "VendorSuspended", "VendorReinstated", "VendorProfileUpdated",
                "VendorCommissionChanged"), 1, event -> {
            VendorPayload p = support.payload(event, VendorPayload.class);
            VendorProjection v = vendors.findForUpdate(p.vendorId())
                    .orElseGet(() -> new VendorProjection(p.vendorId(), p.ownerUserId()));
            if (v.apply(p.businessName(), p.slug(), p.status(), p.vendorVersion())) {
                vendors.save(v);
                // product detail JSON embeds vendor name and visibility depends on status
                products.idsByVendor(p.vendorId()).forEach(id -> cache.evictAfterCommit(CacheKeys.product(id)));
            }
        });
    }

    @KafkaListener(topics = "inventory.events", groupId = "catalog-service.stock")
    public void onStock(String message) {
        support.handleOnce(message, "catalog-service.stock", Set.of("StockLevelChanged"), 1, event -> {
            StockPayload p = support.payload(event, StockPayload.class);
            if (products.updateVariantStock(p.variantId(), p.available(), p.stockVersion()) > 0) {
                products.refreshInStock(p.productId());
                cache.evictAfterCommit(CacheKeys.product(p.productId()));
            }
        });
    }

    @KafkaListener(topics = "review.events", groupId = "catalog-service.ratings")
    public void onRating(String message) {
        support.handleOnce(message, "catalog-service.ratings", Set.of("ProductRatingUpdated"), 1, event -> {
            RatingPayload p = support.payload(event, RatingPayload.class);
            if (products.updateRating(p.productId(), p.averageRating(), p.reviewCount()) > 0) {
                cache.evictAfterCommit(CacheKeys.product(p.productId()));
            }
        });
    }
}
