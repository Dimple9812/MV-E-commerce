package com.marketflow.order.api;

import com.marketflow.order.domain.Order;
import com.marketflow.order.domain.OrderItem;
import com.marketflow.order.domain.OrderStatusHistory;
import com.marketflow.order.domain.Suborder;
import com.marketflow.order.domain.saga.CheckoutSaga;
import com.marketflow.order.domain.saga.SagaTransition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    // ---------- requests ----------

    /** Prices are deliberately NOT part of the request: the server prices every line itself. */
    public record CheckoutItem(@NotNull UUID variantId, @NotNull @Min(1) @Max(100) Integer quantity) {
    }

    public record Address(@NotBlank @Size(max = 120) String recipient,
                          @NotBlank @Size(max = 200) String line1,
                          @Size(max = 200) String line2,
                          @NotBlank @Size(max = 100) String city,
                          @Size(max = 100) String state,
                          @NotBlank @Size(max = 20) String postalCode,
                          @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String country,
                          @Size(max = 30) @Pattern(regexp = "^[0-9+()\\-\\s]*$") String phone) {
        public Map<String, String> toMap() {
            java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
            m.put("recipient", recipient);
            m.put("line1", line1);
            if (line2 != null) {
                m.put("line2", line2);
            }
            m.put("city", city);
            if (state != null) {
                m.put("state", state);
            }
            m.put("postalCode", postalCode);
            m.put("country", country.toUpperCase(java.util.Locale.ROOT));
            if (phone != null) {
                m.put("phone", phone);
            }
            return m;
        }
    }

    public record CheckoutRequest(@NotEmpty @Size(max = 50) List<@Valid @NotNull CheckoutItem> items,
                                  @NotNull @Valid Address shippingAddress,
                                  @Size(max = 40) String couponCode,
                                  @NotBlank @Pattern(regexp = "^tok_[a-z0-9_]{1,60}$",
                                          message = "must be a sandbox payment token such as tok_visa")
                                  String paymentToken) {
    }

    public record CancelRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record ShipRequest(@NotBlank @Size(max = 60) String carrier,
                              @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9\\-]+$") String trackingNumber) {
    }

    // ---------- views ----------

    public record ItemView(UUID id, UUID productId, UUID variantId, String sku, String productName, String variantName,
                           String imageUrl, int quantity, BigDecimal unitPrice, BigDecimal lineSubtotal,
                           BigDecimal discountAmount, BigDecimal lineTotal) {
        public static ItemView of(OrderItem i) {
            return new ItemView(i.getId(), i.getProductId(), i.getVariantId(), i.getSku(), i.getProductName(),
                    i.getVariantName(), i.getImageUrl(), i.getQuantity(), i.getUnitPrice(), i.getLineSubtotal(),
                    i.getDiscountAmount(), i.getLineTotal());
        }
    }

    /** Customer view of a vendor suborder: no commission or vendor-net amounts. */
    public record SuborderView(UUID id, UUID vendorId, String vendorName, String status, BigDecimal itemsSubtotal,
                               BigDecimal discountAmount, BigDecimal shippingAmount, BigDecimal taxAmount,
                               BigDecimal total, String carrier, String trackingNumber, Instant shippedAt,
                               Instant deliveredAt, List<ItemView> items) {
        public static SuborderView of(Suborder s) {
            return new SuborderView(s.getId(), s.getVendorId(), s.getVendorName(), s.getStatus().name(),
                    s.getItemsSubtotal(), s.getDiscountAmount(), s.getShippingAmount(), s.getTaxAmount(),
                    s.getTotal(), s.getCarrier(), s.getTrackingNumber(), s.getShippedAt(), s.getDeliveredAt(),
                    s.getItems().stream().map(ItemView::of).toList());
        }
    }

    public record HistoryView(UUID suborderId, String fromStatus, String toStatus, String reason, Instant at) {
        public static HistoryView of(OrderStatusHistory h) {
            return new HistoryView(h.getSuborderId(), h.getFromStatus(), h.getToStatus(), h.getReason(),
                    h.getOccurredAt());
        }
    }

    public record OrderView(UUID id, String status, String checkoutState, String currency, BigDecimal itemsSubtotal,
                            BigDecimal discountTotal, BigDecimal shippingTotal, BigDecimal taxTotal,
                            BigDecimal grandTotal, String couponCode, Map<String, String> shippingAddress,
                            String cancelReason, Instant createdAt, Instant confirmedAt, Instant cancelledAt,
                            List<SuborderView> suborders, List<HistoryView> history) {
        public static OrderView of(Order o, CheckoutSaga saga, List<OrderStatusHistory> history) {
            return new OrderView(o.getId(), o.getStatus().name(), saga == null ? null : saga.getState().name(),
                    o.getCurrency(), o.getItemsSubtotal(), o.getDiscountTotal(), o.getShippingTotal(),
                    o.getTaxTotal(), o.getGrandTotal(), o.getCouponCode(), o.getShippingAddress(),
                    o.getCancelReason(), o.getCreatedAt(), o.getConfirmedAt(), o.getCancelledAt(),
                    o.getSuborders().stream().map(SuborderView::of).toList(),
                    history == null ? List.of() : history.stream().map(HistoryView::of).toList());
        }
    }

    public record OrderSummaryView(UUID id, String status, BigDecimal grandTotal, String currency, int itemCount,
                                   int vendorCount, Instant createdAt) {
        public static OrderSummaryView of(Order o) {
            return new OrderSummaryView(o.getId(), o.getStatus().name(), o.getGrandTotal(), o.getCurrency(),
                    o.getSuborders().stream().mapToInt(s -> s.getItems().stream().mapToInt(OrderItem::getQuantity)
                            .sum()).sum(), o.getSuborders().size(), o.getCreatedAt());
        }
    }

    /** Vendor view of its own suborder, including commission and earnings. */
    public record VendorSuborderView(UUID id, UUID orderId, String status, String orderStatus,
                                     Map<String, String> shippingAddress, BigDecimal itemsSubtotal,
                                     BigDecimal discountAmount, BigDecimal shippingAmount, BigDecimal total,
                                     BigDecimal commissionRate, BigDecimal commissionAmount,
                                     BigDecimal vendorNetAmount, String carrier, String trackingNumber,
                                     Instant orderedAt, Instant shippedAt, Instant deliveredAt,
                                     List<ItemView> items) {
        public static VendorSuborderView of(Suborder s) {
            Order o = s.getOrder();
            return new VendorSuborderView(s.getId(), o.getId(), s.getStatus().name(), o.getStatus().name(),
                    o.getShippingAddress(), s.getItemsSubtotal(), s.getDiscountAmount(), s.getShippingAmount(),
                    s.getTotal(), s.getCommissionRate(), s.getCommissionAmount(), s.getVendorNetAmount(),
                    s.getCarrier(), s.getTrackingNumber(), o.getCreatedAt(), s.getShippedAt(), s.getDeliveredAt(),
                    s.getItems().stream().map(ItemView::of).toList());
        }
    }

    public record TransitionView(String from, String to, String trigger, UUID eventId, String detail, Instant at) {
        public static TransitionView of(SagaTransition t) {
            return new TransitionView(t.getFromState(), t.getToState(), t.getTrigger(), t.getEventId(), t.getDetail(),
                    t.getOccurredAt());
        }
    }

    public record SagaView(UUID orderId, String state, int attempt, Instant deadlineAt, String compensationReason,
                           String lastError, Instant createdAt, Instant updatedAt, Instant completedAt,
                           List<TransitionView> transitions) {
        public static SagaView of(CheckoutSaga s, List<SagaTransition> transitions) {
            return new SagaView(s.getOrderId(), s.getState().name(), s.getAttempt(), s.getDeadlineAt(),
                    s.getCompensationReason(), s.getLastError(), s.getCreatedAt(), s.getUpdatedAt(),
                    s.getCompletedAt(), transitions.stream().map(TransitionView::of).toList());
        }
    }
}
