package com.marketflow.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Events on {@code payment.events}, keyed by orderId. Contract: contracts/events/payment.events.schema.json */
public final class PaymentEvents {

    public static final String TOPIC = "payment.events";
    public static final String COMMANDS = "payment.commands";
    public static final String AGGREGATE = "Payment";

    private PaymentEvents() {
    }

    public record PaymentOutcome(UUID orderId, UUID paymentId, UUID customerId, BigDecimal amount, String currency,
                                 String status, String code, UUID refundId, BigDecimal refundedAmount) {
    }

    // commands from order-service
    public record AuthorizeCommand(UUID orderId, UUID customerId, BigDecimal amount, String currency,
                                   String paymentToken) {
    }

    public record PaymentCommand(UUID orderId, BigDecimal amount, String currency, String reason) {
    }
}
