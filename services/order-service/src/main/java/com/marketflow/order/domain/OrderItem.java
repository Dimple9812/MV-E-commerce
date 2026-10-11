package com.marketflow.order.domain;

import com.marketflow.order.domain.pricing.PricingCalculator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** Immutable snapshot of what was bought, at which price, from whom. */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "suborder_id")
    private Suborder suborder;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(name = "variant_id", nullable = false)
    private UUID variantId;
    @Column(nullable = false)
    private String sku;
    @Column(name = "product_name", nullable = false)
    private String productName;
    @Column(name = "variant_name", nullable = false)
    private String variantName;
    @Column(name = "image_url")
    private String imageUrl;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;
    @Column(name = "line_subtotal", nullable = false)
    private BigDecimal lineSubtotal;
    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;
    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    protected OrderItem() {
    }

    OrderItem(Order order, Suborder suborder, PricingCalculator.PricedItem pi) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.suborder = suborder;
        this.productId = pi.line().productId();
        this.variantId = pi.line().variantId();
        this.sku = pi.line().sku();
        this.productName = pi.line().productName();
        this.variantName = pi.line().variantName();
        this.imageUrl = pi.line().imageUrl();
        this.quantity = pi.line().quantity();
        this.unitPrice = pi.line().unitPrice();
        this.lineSubtotal = pi.lineSubtotal();
        this.discountAmount = pi.discount();
        this.lineTotal = pi.lineTotal();
    }

    public UUID getId() {
        return id;
    }

    public Suborder getSuborder() {
        return suborder;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getSku() {
        return sku;
    }

    public String getProductName() {
        return productName;
    }

    public String getVariantName() {
        return variantName;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineSubtotal() {
        return lineSubtotal;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
