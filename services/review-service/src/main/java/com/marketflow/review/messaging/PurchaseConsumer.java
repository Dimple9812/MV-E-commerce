package com.marketflow.review.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketflow.platform.kafka.EventListenerSupport;
import com.marketflow.review.application.ReviewService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds the verified-purchase projection from order events. */
@Component
public class PurchaseConsumer {

    static final String CONSUMER = "review-service.purchases";

    private final EventListenerSupport support;
    private final ReviewService reviews;

    public PurchaseConsumer(EventListenerSupport support, ReviewService reviews) {
        this.support = support;
        this.reviews = reviews;
    }

    @KafkaListener(topics = "order.events", groupId = CONSUMER)
    public void onOrder(String message) {
        support.handleOnce(message, CONSUMER, Set.of("OrderConfirmed", "OrderCancelled"), 1, e -> {
            JsonNode p = e.payload();
            UUID order = UUID.fromString(p.path("orderId").asText());
            if (e.eventType().equals("OrderCancelled")) {
                reviews.cancelPurchases(order);
                return;
            }
            List<UUID> products = new ArrayList<>();
            for (JsonNode s : p.path("suborders")) {
                for (JsonNode i : s.path("items")) {
                    products.add(UUID.fromString(i.path("productId").asText()));
                }
            }
            reviews.recordPurchases(order, UUID.fromString(p.path("customerId").asText()), e.occurredAt(), products);
        });
    }
}
