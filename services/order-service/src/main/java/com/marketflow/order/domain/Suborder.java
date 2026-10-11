package com.marketflow.order.domain;

import com.marketflow.order.domain.pricing.PricingCalculator;
import com.marketflow.platform.web.ApiException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The part of an order fulfilled by one vendor. Vendors only ever see their own suborders. */
@Entity
@Table(name = "suborder")
public class Suborder {

    public enum Status { PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED }

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;
    @Column(name = "vendor_name", nullable = false)
    private String vendorName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "items_subtotal", nullable = false)
    private BigDecimal itemsSubtotal;
    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;
    @Column(name = "shipping_amount", nullable = false)
    private BigDecimal shippingAmount;
    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;
    @Column(nullable = false)
    private BigDecimal total;
    @Column(name = "commission_rate", nullable = false)
    private BigDecimal commissionRate;
    @Column(name = "commission_amount", nullable = false)
    private BigDecimal commissionAmount;
    @Column(name = "vendor_net_amount", nullable = false)
    private BigDecimal vendorNetAmount;
    private String carrier;
    @Column(name = "tracking_number")
    private String trackingNumber;
    @Column(name = "shipped_at")
    private Instant shippedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Version
    private Long version; // null until first persist, so Spring Data treats assigned-ID entities as new

    @OneToMany(mappedBy = "suborder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Suborder() {
    }

    Suborder(Order order, PricingCalculator.PricedSuborder ps) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.vendorId = ps.vendorId();
        this.vendorName = ps.vendorName();
        this.status = Status.PENDING;
        this.itemsSubtotal = ps.itemsSubtotal();
        this.discountAmount = ps.discount();
        this.shippingAmount = ps.shipping();
        this.taxAmount = ps.tax();
        this.total = ps.total();
        this.commissionRate = ps.commissionRate();
        this.commissionAmount = ps.commission();
        this.vendorNetAmount = ps.vendorNet();
        for (PricingCalculator.PricedItem pi : ps.items()) {
            items.add(new OrderItem(order, this, pi));
        }
    }

    void confirm() {
        if (status == Status.PENDING) {
            status = Status.CONFIRMED;
        }
    }

    void cancel() {
        if (status == Status.PENDING || status == Status.CONFIRMED) {
            status = Status.CANCELLED;
        }
    }

    public void ship(String carrier, String trackingNumber) {
        if (status != Status.CONFIRMED) {
            throw ApiException.conflict("invalid_suborder_transition", "Only confirmed suborders can be shipped");
        }
        this.status = Status.SHIPPED;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.shippedAt = Instant.now();
    }

    public void deliver() {
        if (status != Status.SHIPPED) {
            throw ApiException.conflict("invalid_suborder_transition", "Only shipped suborders can be delivered");
        }
        this.status = Status.DELIVERED;
        this.deliveredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getVendorName() {
        return vendorName;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getItemsSubtotal() {
        return itemsSubtotal;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getShippingAmount() {
        return shippingAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public BigDecimal getVendorNetAmount() {
        return vendorNetAmount;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
