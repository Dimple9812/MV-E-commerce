package com.marketflow.order.application;

import com.marketflow.order.domain.Order;
import com.marketflow.order.domain.OrderItem;
import com.marketflow.order.domain.Suborder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Commands sent by the saga and events published by order-service.
 * Contracts: contracts/events/{order.events,inventory.commands,payment.commands}.schema.json
 */
public final class OrderMessages {

    public static final String ORDER_EVENTS = "order.events";
    public static final String INVENTORY_COMMANDS = "inventory.commands";
    public static final String PAYMENT_COMMANDS = "payment.commands";
    public static final String AGGREGATE = "Order";

    private OrderMessages() {
    }

    // ---------- commands ----------

    public record InventoryLine(UUID variantId, String sku, int quantity) {
    }

    public record InventoryCommand(UUID orderId, List<InventoryLine> lines, String reason) {
    }

    public record PaymentAuthorizationRequested(UUID orderId, UUID customerId, BigDecimal amount, String currency,
                                                String paymentToken) {
    }

    public record PaymentCommand(UUID orderId, BigDecimal amount, String currency, String reason) {
    }

    // ---------- events ----------

    public record ItemView(UUID productId, UUID variantId, String sku, String productName, String variantName,
                           int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
        static ItemView of(OrderItem i) {
            return new ItemView(i.getProductId(), i.getVariantId(), i.getSku(), i.getProductName(), i.getVariantName(),
                    i.getQuantity(), i.getUnitPrice(), i.getLineTotal());
        }
    }

    public record SuborderView(UUID suborderId, UUID vendorId, String vendorName, String status,
                               BigDecimal itemsSubtotal, BigDecimal discountAmount, BigDecimal shippingAmount,
                               BigDecimal taxAmount, BigDecimal total, BigDecimal commissionRate,
                               BigDecimal commissionAmount, BigDecimal vendorNetAmount, List<ItemView> items) {
        static SuborderView of(Suborder s) {
            return new SuborderView(s.getId(), s.getVendorId(), s.getVendorName(), s.getStatus().name(),
                    s.getItemsSubtotal(), s.getDiscountAmount(), s.getShippingAmount(), s.getTaxAmount(), s.getTotal(),
                    s.getCommissionRate(), s.getCommissionAmount(), s.getVendorNetAmount(),
                    s.getItems().stream().map(ItemView::of).toList());
        }
    }

    /** Payload of OrderCreated / OrderConfirmed / OrderCancelled. */
    public record OrderSnapshot(UUID orderId, UUID customerId, String status, String currency,
                                BigDecimal itemsSubtotal, BigDecimal discountTotal, BigDecimal shippingTotal,
                                BigDecimal taxTotal, BigDecimal totalAmount, String reason, boolean wasConfirmed,
                                List<SuborderView> suborders) {
        public static OrderSnapshot of(Order o, String reason, boolean wasConfirmed) {
            return new OrderSnapshot(o.getId(), o.getCustomerId(), o.getStatus().name(), o.getCurrency(),
                    o.getItemsSubtotal(), o.getDiscountTotal(), o.getShippingTotal(), o.getTaxTotal(),
                    o.getGrandTotal(), reason, wasConfirmed, o.getSuborders().stream().map(SuborderView::of).toList());
        }
    }

    public record ShipmentCreated(UUID orderId, UUID suborderId, UUID vendorId, UUID customerId, String carrier,
                                  String trackingNumber) {
    }

    public record SuborderDelivered(UUID orderId, UUID suborderId, UUID vendorId, UUID customerId,
                                    List<UUID> productIds) {
    }

    public record OrderStatusChanged(UUID orderId, UUID customerId, String status, String reason) {
    }

    public record RefundRequestedEvent(UUID orderId, UUID customerId, BigDecimal amount, String reason) {
    }
}
