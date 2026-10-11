package com.marketflow.catalog.domain;

import com.marketflow.platform.web.ApiException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Product aggregate (product + variants + images). All modifications go through this class so the invariants
 * (unique SKUs within the product, publishable only with an active variant, denormalised min/max price) hold.
 */
@Entity
@Table(name = "product")
public class Product {

    public enum Status { DRAFT, PUBLISHED, ARCHIVED, BLOCKED }

    public static final int MAX_VARIANTS = 50;
    public static final int MAX_IMAGES = 10;

    @Id
    private UUID id;
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String slug;
    @Column(nullable = false)
    private String description = "";
    private String brand;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> attributes = new HashMap<>();
    @Column(nullable = false, length = 3)
    private String currency = "USD";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;
    @Column(name = "moderation_note")
    private String moderationNote;
    @Column(name = "min_price")
    private BigDecimal minPrice;
    @Column(name = "max_price")
    private BigDecimal maxPrice;
    @Column(name = "in_stock", nullable = false)
    private boolean inStock;
    @Column(name = "rating_avg", nullable = false)
    private BigDecimal ratingAvg = BigDecimal.ZERO;
    @Column(name = "rating_count", nullable = false)
    private int ratingCount;
    @Column(name = "max_per_order", nullable = false)
    private int maxPerOrder = 10;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private Long version; // null until first persist, so Spring Data treats assigned-ID entities as new

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, createdAt ASC")
    private List<ProductVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    protected Product() {
    }

    public static Product draft(UUID vendorId, UUID categoryId, String name, String slug, String description,
                                String brand, Map<String, String> attributes, int maxPerOrder) {
        Product p = new Product();
        p.id = UUID.randomUUID();
        p.vendorId = vendorId;
        p.createdAt = Instant.now();
        p.updateDetails(categoryId, name, slug, description, brand, attributes, maxPerOrder);
        return p;
    }

    public void updateDetails(UUID categoryId, String name, String slug, String description, String brand,
                              Map<String, String> attributes, int maxPerOrder) {
        requireEditable();
        if (maxPerOrder < 1 || maxPerOrder > 100) {
            throw ApiException.badRequest("invalid_purchase_limit", "maxPerOrder must be between 1 and 100");
        }
        this.categoryId = categoryId;
        this.name = name.trim();
        this.slug = slug;
        this.description = description == null ? "" : description;
        this.brand = brand;
        this.attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
        this.maxPerOrder = maxPerOrder;
        touch();
    }

    public ProductVariant addVariant(String sku, String name, Map<String, String> attrs, BigDecimal price,
                                     BigDecimal compareAt) {
        requireEditable();
        if (variants.size() >= MAX_VARIANTS) {
            throw ApiException.unprocessable("variant_limit", "At most " + MAX_VARIANTS + " variants per product");
        }
        String normalized = ProductVariant.normalizeSku(sku);
        if (variants.stream().anyMatch(v -> v.getSku().equals(normalized))) {
            throw ApiException.conflict("duplicate_sku", "SKU " + normalized + " already exists on this product");
        }
        ProductVariant v = new ProductVariant(this, normalized, name, attrs, price, compareAt, variants.size());
        variants.add(v);
        recomputePrices();
        touch();
        return v;
    }

    /** @return the previous price if it changed */
    public Optional<BigDecimal> updateVariant(UUID variantId, String name, Map<String, String> attrs,
                                              BigDecimal price, BigDecimal compareAt, boolean active) {
        requireEditable();
        ProductVariant v = variant(variantId);
        BigDecimal old = v.getPrice();
        v.update(name, attrs, price, compareAt, active, v.getSortOrder());
        if (status == Status.PUBLISHED && variants.stream().noneMatch(ProductVariant::isActive)) {
            throw ApiException.unprocessable("no_active_variant", "A published product needs an active variant");
        }
        recomputePrices();
        touch();
        return old.compareTo(v.getPrice()) == 0 ? Optional.empty() : Optional.of(old);
    }

    public ProductVariant variant(UUID variantId) {
        return variants.stream().filter(v -> v.getId().equals(variantId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Variant"));
    }

    public ProductImage addImage(String url, String alt) {
        requireEditable();
        if (images.size() >= MAX_IMAGES) {
            throw ApiException.unprocessable("image_limit", "At most " + MAX_IMAGES + " images per product");
        }
        ProductImage img = new ProductImage(this, url, alt, images.size());
        images.add(img);
        touch();
        return img;
    }

    public void removeImage(UUID imageId) {
        requireEditable();
        if (!images.removeIf(i -> i.getId().equals(imageId))) {
            throw ApiException.notFound("Image");
        }
        touch();
    }

    public void publish(boolean vendorApproved) {
        if (!vendorApproved) {
            throw ApiException.forbidden("Only approved vendors can publish products");
        }
        if (status == Status.BLOCKED) {
            throw ApiException.conflict("product_blocked", "This product was blocked by moderation");
        }
        if (status == Status.PUBLISHED) {
            return;
        }
        if (variants.stream().noneMatch(ProductVariant::isActive)) {
            throw ApiException.unprocessable("no_active_variant", "Add at least one active variant before publishing");
        }
        status = Status.PUBLISHED;
        if (publishedAt == null) {
            publishedAt = Instant.now();
        }
        touch();
    }

    public void unpublish() {
        if (status != Status.PUBLISHED) {
            throw ApiException.conflict("not_published", "Product is not published");
        }
        status = Status.DRAFT;
        touch();
    }

    public void archive() {
        if (status == Status.BLOCKED) {
            throw ApiException.conflict("product_blocked", "Blocked products are managed by moderators");
        }
        status = Status.ARCHIVED;
        touch();
    }

    public void block(String note) {
        status = Status.BLOCKED;
        moderationNote = note;
        touch();
    }

    public void unblock() {
        if (status != Status.BLOCKED) {
            throw ApiException.conflict("not_blocked", "Product is not blocked");
        }
        status = Status.DRAFT;
        moderationNote = null;
        touch();
    }

    /** Hard deletion is only allowed for drafts that were never visible to customers. */
    public boolean isDeletable() {
        return status == Status.DRAFT && publishedAt == null;
    }

    private void requireEditable() {
        if (status == Status.BLOCKED) {
            throw ApiException.conflict("product_blocked", "This product was blocked by moderation");
        }
    }

    private void recomputePrices() {
        minPrice = variants.stream().filter(ProductVariant::isActive).map(ProductVariant::getPrice)
                .min(Comparator.naturalOrder()).orElse(null);
        maxPrice = variants.stream().filter(ProductVariant::isActive).map(ProductVariant::getPrice)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public String getBrand() {
        return brand;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public String getModerationNote() {
        return moderationNote;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public BigDecimal getMaxPrice() {
        return maxPrice;
    }

    public boolean isInStock() {
        return inStock;
    }

    public BigDecimal getRatingAvg() {
        return ratingAvg;
    }

    public int getRatingCount() {
        return ratingCount;
    }

    public int getMaxPerOrder() {
        return maxPerOrder;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }

    public List<ProductVariant> getVariants() {
        return variants;
    }

    public List<ProductImage> getImages() {
        return images;
    }
}
