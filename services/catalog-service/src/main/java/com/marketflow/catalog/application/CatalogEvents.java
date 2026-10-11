package com.marketflow.catalog.application;

import com.marketflow.catalog.domain.Product;
import com.marketflow.catalog.domain.ProductVariant;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Events on {@code catalog.product.events}, keyed by productId (per-product ordering). Every event carries the
 * full {@link ProductSnapshot} plus {@code productVersion}, so consumers (inventory, order) can upsert idempotently
 * and ignore stale/out-of-order deliveries by comparing versions.
 */
public final class CatalogEvents {

    public static final String TOPIC = "catalog.product.events";
    public static final String AGGREGATE = "Product";

    private CatalogEvents() {
    }

    public record VariantSnapshot(UUID variantId, String sku, String name, BigDecimal price, boolean active) {
    }

    /** @param priceChange only present on ProductPriceChanged (optional field, schema v1 compatible) */
    public record ProductSnapshot(UUID productId, UUID vendorId, UUID categoryId, String name, String slug,
                                  String status, String currency, int maxPerOrder, String imageUrl,
                                  long productVersion, List<VariantSnapshot> variants, PriceChange priceChange) {

        public static ProductSnapshot of(Product p) {
            return of(p, null);
        }

        public static ProductSnapshot of(Product p, PriceChange change) {
            return new ProductSnapshot(p.getId(), p.getVendorId(), p.getCategoryId(), p.getName(), p.getSlug(),
                    p.getStatus().name(), p.getCurrency(), p.getMaxPerOrder(),
                    p.getImages().isEmpty() ? null : p.getImages().get(0).getUrl(), p.getVersion(),
                    p.getVariants().stream().map(CatalogEvents::variant).toList(), change);
        }
    }

    public record PriceChange(UUID variantId, String sku, BigDecimal oldPrice, BigDecimal newPrice) {
    }

    private static VariantSnapshot variant(ProductVariant v) {
        return new VariantSnapshot(v.getId(), v.getSku(), v.getName(), v.getPrice(), v.isActive());
    }
}
