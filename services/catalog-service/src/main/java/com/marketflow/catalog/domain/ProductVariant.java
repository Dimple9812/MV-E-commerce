package com.marketflow.catalog.domain;

import com.marketflow.platform.money.Money;
import com.marketflow.platform.web.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "product_variant")
public class ProductVariant {

    public static final BigDecimal MAX_PRICE = new BigDecimal("999999.99");

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;
    @Column(nullable = false, unique = true)
    private String sku;
    @Column(nullable = false)
    private String name;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> attributes = new HashMap<>();
    @Column(nullable = false)
    private BigDecimal price;
    @Column(name = "compare_at_price")
    private BigDecimal compareAtPrice;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "available_qty", nullable = false)
    private int availableQty;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProductVariant() {
    }

    ProductVariant(Product product, String sku, String name, Map<String, String> attributes, BigDecimal price,
                   BigDecimal compareAtPrice, int sortOrder) {
        this.id = UUID.randomUUID();
        this.product = product;
        this.createdAt = Instant.now();
        this.sku = normalizeSku(sku);
        update(name, attributes, price, compareAtPrice, true, sortOrder);
    }

    void update(String name, Map<String, String> attributes, BigDecimal price, BigDecimal compareAtPrice,
                boolean active, int sortOrder) {
        validatePrice(price);
        if (compareAtPrice != null && compareAtPrice.compareTo(price) <= 0) {
            throw ApiException.badRequest("invalid_price", "compareAtPrice must be greater than price");
        }
        this.name = name;
        this.attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
        this.price = Money.normalize(price);
        this.compareAtPrice = compareAtPrice == null ? null : Money.normalize(compareAtPrice);
        this.active = active;
        this.sortOrder = sortOrder;
    }

    static void validatePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0 || price.scale() > 2) {
            throw ApiException.badRequest("invalid_price",
                    "Price must be positive, at most " + MAX_PRICE + " and have at most 2 decimals");
        }
    }

    static String normalizeSku(String sku) {
        String s = sku == null ? "" : sku.trim().toUpperCase(java.util.Locale.ROOT);
        if (!s.matches("^[A-Z0-9][A-Z0-9\\-_.]{1,63}$")) {
            throw ApiException.badRequest("invalid_sku", "SKU must be 2-64 characters of A-Z, 0-9, '-', '_' or '.'");
        }
        return s;
    }

    public void setAvailableQty(int qty) {
        this.availableQty = Math.max(0, qty);
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getCompareAtPrice() {
        return compareAtPrice;
    }

    public boolean isActive() {
        return active;
    }

    public int getAvailableQty() {
        return availableQty;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
