package com.marketflow.vendor.messaging;

import com.marketflow.platform.kafka.EventListenerSupport;
import com.marketflow.vendor.application.EarningsService;
import com.marketflow.vendor.domain.EarningsEntry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds the vendor earnings ledger from order events (eventually consistent with order-service). */
@Component
public class OrderEventsConsumer {

    static final String CONSUMER = "vendor-service.earnings";

    private final EventListenerSupport support;
    private final EarningsService earnings;

    public OrderEventsConsumer(EventListenerSupport support, EarningsService earnings) {
        this.support = support;
        this.earnings = earnings;
    }

    public record OrderPayload(UUID orderId, String currency, Instant occurredAt, boolean wasConfirmed,
                               List<EarningsService.SuborderAmounts> suborders) {
    }

    @KafkaListener(topics = "order.events", groupId = CONSUMER)
    public void onOrderEvent(String message) {
        support.handleOnce(message, CONSUMER, Set.of("OrderConfirmed", "OrderCancelled"), 1, event -> {
            OrderPayload p = support.payload(event, OrderPayload.class);
            if (p.suborders() == null) {
                return;
            }
            if ("OrderConfirmed".equals(event.eventType())) {
                earnings.record(p.orderId(), p.currency(), event.occurredAt(), p.suborders(), EarningsEntry.Type.SALE);
            } else if (p.wasConfirmed()) {
                // cancellation after confirmation = refund; pre-confirmation cancellations never earned anything
                earnings.record(p.orderId(), p.currency(), event.occurredAt(), p.suborders(),
                        EarningsEntry.Type.REFUND);
            }
        });
    }
}
