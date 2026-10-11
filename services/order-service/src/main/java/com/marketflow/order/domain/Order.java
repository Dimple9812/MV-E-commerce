package com.marketflow.order.domain;

import com.marketflow.order.domain.pricing.PricingCalculator;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customer-facing parent order. One {@link Suborder} per vendor holds the vendor's items and amounts. */
@Entity
@Table(name = "customer_order")
public class Order {

    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "customer_email")
    private String customerEmail;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "items_subtotal", nullable = false)
    private BigDecimal itemsSubtotal;
    @Column(name = "discount_total", nullable = false)
    private BigDecimal discountTotal;
    @Column(name = "shipping_total", nullable = false)
    private BigDecimal shippingTotal;
    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal;
    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal;
    @Column(name = "coupon_code")
    private String couponCode;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address", nullable = false)
    private Map<String, String> shippingAddress;
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false)
    private String requestHash;
    @Column(name = "cancel_reason")
    private String cancelReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Version
    private Long version; // null until first persist, so Spring Data treats assigned-ID entities as new

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("vendorName ASC")
    private List<Suborder> suborders = new ArrayList<>();

    protected Order() {
    }

    public static Order place(UUID customerId, String customerEmail, String idempotencyKey, String requestHash,
                              Map<String, String> address, String currency, PricingCalculator.Quote quote) {
        Order o = new Order();
        o.id = UUID.randomUUID();
        o.customerId = customerId;
        o.customerEmail = customerEmail;
        o.status = OrderStatus.PENDING;
        o.currency = currency;
        o.idempotencyKey = idempotencyKey;
        o.requestHash = requestHash;
        o.shippingAddress = address;
        o.itemsSubtotal = quote.itemsSubtotal();
        o.discountTotal = quote.discountTotal();
        o.shippingTotal = quote.shippingTotal();
        o.taxTotal = quote.taxTotal();
        o.grandTotal = quote.grandTotal();
        o.couponCode = quote.couponCode();
        o.createdAt = Instant.now();
        o.updatedAt = o.createdAt;
        for (PricingCalculator.PricedSuborder ps : quote.suborders()) {
            o.suborders.add(new Suborder(o, ps));
        }
        return o;
    }

    public OrderStatus confirm() {
        OrderStatus prev = status;
        status = OrderStatus.CONFIRMED;
        confirmedAt = Instant.now();
        suborders.forEach(Suborder::confirm);
        touch();
        return prev;
    }

    public OrderStatus cancel(String reason) {
        OrderStatus prev = status;
        status = OrderStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = Instant.now();
        suborders.forEach(Suborder::cancel);
        touch();
        return prev;
    }

    public OrderStatus fail(String reason) {
        OrderStatus prev = status;
        status = OrderStatus.FAILED;
        cancelReason = reason;
        touch();
        return prev;
    }

    public OrderStatus requestCancellation(String reason) {
        if (status != OrderStatus.CONFIRMED) {
            throw ApiException.conflict("not_cancellable",
                    status == OrderStatus.PENDING ? "Checkout is still in progress; try again in a moment"
                            : "Orders in status " + status + " cannot be cancelled");
        }
        if (suborders.stream().anyMatch(s -> s.getStatus() != Suborder.Status.CONFIRMED)) {
            throw ApiException.conflict("not_cancellable", "Part of this order has already shipped");
        }
        OrderStatus prev = status;
        status = OrderStatus.CANCELLATION_REQUESTED;
        cancelReason = reason;
        touch();
        return prev;
    }

    public OrderStatus refundFailed(String reason) {
        OrderStatus prev = status;
        status = OrderStatus.REFUND_FAILED;
        cancelReason = reason;
        touch();
        return prev;
    }

    /** Re-derives the parent status from suborder fulfilment. @return previous status if it changed */
    public OrderStatus recomputeFulfilment() {
        if (status != OrderStatus.CONFIRMED && status != OrderStatus.PARTIALLY_SHIPPED
                && status != OrderStatus.SHIPPED) {
            return null;
        }
        long delivered = suborders.stream().filter(s -> s.getStatus() == Suborder.Status.DELIVERED).count();
        long shippedOrDelivered = suborders.stream().filter(s -> s.getStatus() == Suborder.Status.SHIPPED
                || s.getStatus() == Suborder.Status.DELIVERED).count();
        OrderStatus next = delivered == suborders.size() ? OrderStatus.DELIVERED
                : shippedOrDelivered == suborders.size() ? OrderStatus.SHIPPED
                : shippedOrDelivered > 0 ? OrderStatus.PARTIALLY_SHIPPED : OrderStatus.CONFIRMED;
        if (next == status) {
            return null;
        }
        OrderStatus prev = status;
        status = next;
        touch();
        return prev;
    }

    public List<OrderItem> allItems() {
        return suborders.stream().flatMap(s -> s.getItems().stream()).toList();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getItemsSubtotal() {
        return itemsSubtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getShippingTotal() {
        return shippingTotal;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public Map<String, String> getShippingAddress() {
        return shippingAddress;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<Suborder> getSuborders() {
        return suborders;
    }
}
