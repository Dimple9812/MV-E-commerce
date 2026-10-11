package com.marketflow.catalog.application;

import com.marketflow.catalog.api.CatalogDtos.ProductRequest;
import com.marketflow.catalog.api.CatalogDtos.ProductSummaryView;
import com.marketflow.catalog.api.CatalogDtos.ProductView;
import com.marketflow.catalog.api.CatalogDtos.ProductUpdateRequest;
import com.marketflow.catalog.api.CatalogDtos.VariantRequest;
import com.marketflow.catalog.api.CatalogDtos.VariantUpdateRequest;
import com.marketflow.catalog.cache.CacheAside;
import com.marketflow.catalog.cache.CacheKeys;
import com.marketflow.catalog.domain.Product;
import com.marketflow.catalog.domain.ProductVariant;
import com.marketflow.catalog.domain.VendorProjection;
import com.marketflow.catalog.persistence.CategoryRepository;
import com.marketflow.catalog.persistence.ProductRepository;
import com.marketflow.catalog.persistence.VendorProjectionRepository;
import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vendor-side product commands. The acting vendor is always resolved from the authenticated user ID via the
 * vendor projection, and every product lookup is scoped to that vendor ("findOwned"), so a vendor can never
 * read or modify another vendor's product even with a valid product ID.
 */
@Service
public class ProductService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final VendorProjectionRepository vendors;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;
    private final CacheAside cache;
    private final ImageStorage images;

    public ProductService(ProductRepository products, CategoryRepository categories,
                          VendorProjectionRepository vendors, OutboxWriter outbox, AuditRecorder audit,
                          CacheAside cache, ImageStorage images) {
        this.products = products;
        this.categories = categories;
        this.vendors = vendors;
        this.outbox = outbox;
        this.audit = audit;
        this.cache = cache;
        this.images = images;
    }

    /** Resolves the caller's vendor; unknown or non-approved vendors may not manage products. */
    @Transactional(readOnly = true)
    public VendorProjection approvedVendor(UUID userId) {
        VendorProjection v = vendors.findByOwnerUserId(userId)
                .orElseThrow(() -> ApiException.forbidden("No approved vendor account is linked to this user"));
        if (!v.isApproved()) {
            throw ApiException.forbidden("Your vendor account is " + v.getStatus() + "; products cannot be managed");
        }
        return v;
    }

    @Transactional(readOnly = true)
    public Page<ProductSummaryView> list(UUID userId, Product.Status status, Pageable pageable) {
        UUID vendorId = approvedVendor(userId).getVendorId();
        Page<Product> page = status == null ? products.findByVendorIdOrderByUpdatedAtDesc(vendorId, pageable)
                : products.findByVendorIdAndStatusOrderByUpdatedAtDesc(vendorId, status, pageable);
        return page.map(ProductSummaryView::of);
    }

    @Transactional(readOnly = true)
    public ProductView get(UUID userId, UUID productId) {
        return toView(owned(approvedVendor(userId).getVendorId(), productId));
    }

    @Transactional
    public ProductView create(UUID userId, ProductRequest req) {
        VendorProjection vendor = approvedVendor(userId);
        requireCategory(req.categoryId());
        Product p = Product.draft(vendor.getVendorId(), req.categoryId(), req.name(),
                uniqueSlug(vendor.getVendorId(), req.name()), req.description(), req.brand(), req.attributes(),
                req.maxPerOrder() == null ? 10 : req.maxPerOrder());
        if (req.variants() != null) {
            for (VariantRequest v : req.variants()) {
                p.addVariant(v.sku(), v.name(), v.attributes(), v.price(), v.compareAtPrice());
            }
        }
        products.saveAndFlush(p);
        emit(p, "ProductCreated", null);
        return toView(p);
    }

    @Transactional
    public ProductView update(UUID userId, UUID productId, ProductUpdateRequest req) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        requireCategory(req.categoryId());
        String slug = p.getName().equals(req.name().trim()) ? p.getSlug() : uniqueSlug(p.getVendorId(), req.name());
        p.updateDetails(req.categoryId(), req.name(), slug, req.description(), req.brand(), req.attributes(),
                req.maxPerOrder() == null ? p.getMaxPerOrder() : req.maxPerOrder());
        products.flush();
        emit(p, "ProductUpdated", null);
        return toView(p);
    }

    @Transactional
    public ProductView addVariant(UUID userId, UUID productId, VariantRequest req) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        p.addVariant(req.sku(), req.name(), req.attributes(), req.price(), req.compareAtPrice());
        products.flush();
        emit(p, "ProductUpdated", null);
        return toView(p);
    }

    @Transactional
    public ProductView updateVariant(UUID userId, UUID productId, UUID variantId, VariantUpdateRequest req) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        Optional<BigDecimal> oldPrice = p.updateVariant(variantId, req.name(), req.attributes(), req.price(),
                req.compareAtPrice(), req.active());
        products.flush();
        if (oldPrice.isPresent()) {
            ProductVariant v = p.variant(variantId);
            emit(p, "ProductPriceChanged",
                    new CatalogEvents.PriceChange(variantId, v.getSku(), oldPrice.get(), v.getPrice()));
            audit.record(userId, "VENDOR", "product.price_changed", "Product", productId.toString(),
                    Map.of("sku", v.getSku(), "from", oldPrice.get().toPlainString(),
                            "to", v.getPrice().toPlainString()));
        } else {
            emit(p, "ProductUpdated", null);
        }
        return toView(p);
    }

    @Transactional
    public ProductView publish(UUID userId, UUID productId) {
        VendorProjection vendor = approvedVendor(userId);
        Product p = owned(vendor.getVendorId(), productId);
        boolean wasPublished = p.getStatus() == Product.Status.PUBLISHED;
        p.publish(vendor.isApproved());
        products.flush();
        if (!wasPublished) {
            emit(p, "ProductPublished", null);
        }
        return toView(p);
    }

    @Transactional
    public ProductView unpublish(UUID userId, UUID productId) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        p.unpublish();
        products.flush();
        emit(p, "ProductArchived", null);
        return toView(p);
    }

    @Transactional
    public ProductView archive(UUID userId, UUID productId) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        p.archive();
        products.flush();
        emit(p, "ProductArchived", null);
        return toView(p);
    }

    /** Drafts that were never published are deleted; anything customers may have seen is archived instead. */
    @Transactional
    public boolean delete(UUID userId, UUID productId) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        if (!p.isDeletable()) {
            p.archive();
            products.flush();
            emit(p, "ProductArchived", null);
            return false;
        }
        CatalogEvents.ProductSnapshot last = CatalogEvents.ProductSnapshot.of(p);
        products.delete(p);
        outbox.append(CatalogEvents.TOPIC, "ProductDeleted", 1, CatalogEvents.AGGREGATE, p.getId().toString(),
                new CatalogEvents.ProductSnapshot(last.productId(), last.vendorId(), last.categoryId(), last.name(),
                        last.slug(), "DELETED", last.currency(), last.maxPerOrder(), last.imageUrl(),
                        last.productVersion() + 1, last.variants(), null));
        cache.evictAfterCommit(CacheKeys.product(p.getId()));
        return true;
    }

    @Transactional
    public ProductView addImageUrl(UUID userId, UUID productId, String url, String alt) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        p.addImage(url, alt);
        products.flush();
        emit(p, "ProductUpdated", null);
        return toView(p);
    }

    @Transactional
    public ProductView uploadImage(UUID userId, UUID productId, byte[] bytes, String alt) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        p.addImage(images.store(bytes), alt);
        products.flush();
        emit(p, "ProductUpdated", null);
        return toView(p);
    }

    @Transactional
    public ProductView removeImage(UUID userId, UUID productId, UUID imageId) {
        Product p = owned(approvedVendor(userId).getVendorId(), productId);
        p.removeImage(imageId);
        products.flush();
        emit(p, "ProductUpdated", null);
        return toView(p);
    }

    // ---------- moderation (admin) ----------

    @Transactional
    public ProductView block(UUID adminId, UUID productId, String note) {
        Product p = products.findDetailed(productId).orElseThrow(() -> ApiException.notFound("Product"));
        p.block(note);
        products.flush();
        emit(p, "ProductBlocked", null);
        audit.record(adminId, "ADMIN", "product.blocked", "Product", productId.toString(), Map.of("note", note));
        return toView(p);
    }

    @Transactional
    public ProductView unblock(UUID adminId, UUID productId) {
        Product p = products.findDetailed(productId).orElseThrow(() -> ApiException.notFound("Product"));
        p.unblock();
        products.flush();
        emit(p, "ProductUpdated", null);
        audit.record(adminId, "ADMIN", "product.unblocked", "Product", productId.toString(), Map.of());
        return toView(p);
    }

    // ---------- helpers ----------

    /** DTOs are built inside the transaction (open-in-view is disabled). */
    private ProductView toView(Product p) {
        return ProductView.of(p, vendors.findById(p.getVendorId()).orElse(null));
    }

    private Product owned(UUID vendorId, UUID productId) {
        return products.findOwned(productId, vendorId).orElseThrow(() -> ApiException.notFound("Product"));
    }

    private void requireCategory(UUID categoryId) {
        if (!categories.existsById(categoryId)) {
            throw ApiException.badRequest("invalid_category", "Unknown category");
        }
    }

    private void emit(Product p, String type, CatalogEvents.PriceChange change) {
        outbox.append(CatalogEvents.TOPIC, type, 1, CatalogEvents.AGGREGATE, p.getId().toString(),
                CatalogEvents.ProductSnapshot.of(p, change));
        cache.evictAfterCommit(CacheKeys.product(p.getId()));
    }

    private String uniqueSlug(UUID vendorId, String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "product";
        }
        if (base.length() > 200) {
            base = base.substring(0, 200);
        }
        String slug = base;
        int i = 2;
        while (products.existsByVendorIdAndSlug(vendorId, slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }
}
