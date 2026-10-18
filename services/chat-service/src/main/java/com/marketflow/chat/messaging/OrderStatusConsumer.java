package com.marketflow.chat.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketflow.chat.application.MessageService;
import com.marketflow.chat.domain.Conversation;
import com.marketflow.chat.persistence.ConversationRepository;
import com.marketflow.chat.persistence.ProjectionRepository;
import com.marketflow.platform.events.EventEnvelope;
import com.marketflow.platform.kafka.EventListenerSupport;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Order events → suborder projection (who bought what from whom, for order-scoped conversations and order cards)
 * and SYSTEM messages in existing conversations about that suborder: confirmed, shipped (carrier and tracking),
 * delivered, cancelled.
 *
 * <p>The inbox guard runs the handler and the inserts in one transaction, so a redelivered event never posts the
 * same status message twice. SYSTEM messages are not announced as ChatMessageSent: notification-service already
 * notifies buyers about these order events.
 */
@Component
public class OrderStatusConsumer {

    static final String CONSUMER = "chat-service.orders";
    static final Set<String> TYPES = Set.of("OrderCreated", "OrderConfirmed", "OrderCancelled", "ShipmentCreated",
            "SuborderDelivered");

    private static final Map<String, String> CANCEL_REASONS = Map.of(
            "CUSTOMER_REQUEST", "at the buyer's request",
            "OUT_OF_STOCK", "because an item was out of stock",
            "PAYMENT_DECLINED", "because the payment was declined",
            "CAPTURE_FAILED", "because the payment could not be completed",
            "STOCK_UNAVAILABLE_AFTER_PAYMENT", "because an item became unavailable (the payment is refunded)",
            "INVENTORY_TIMEOUT", "because stock could not be reserved in time");

    private final EventListenerSupport support;
    private final ProjectionRepository projections;
    private final ConversationRepository conversations;
    private final MessageService messages;
    private final ObjectMapper json;

    public OrderStatusConsumer(EventListenerSupport support, ProjectionRepository projections,
                               ConversationRepository conversations, MessageService messages, ObjectMapper json) {
        this.support = support;
        this.projections = projections;
        this.conversations = conversations;
        this.messages = messages;
        this.json = json;
    }

    @KafkaListener(topics = "order.events", groupId = CONSUMER)
    public void onOrderEvent(String message) {
        support.handleOnce(message, CONSUMER, TYPES, 1, this::apply);
    }

    void apply(EventEnvelope e) {
        JsonNode p = e.payload();
        switch (e.eventType()) {
            case "OrderCreated" -> project(e, p);
            case "OrderConfirmed" -> {
                project(e, p);
                forEachSuborder(p, sub -> post(sub, "Order confirmed. The shop is preparing it.",
                        meta("ORDER_CONFIRMED", p, sub)));
            }
            case "OrderCancelled" -> {
                project(e, p);
                String reason = CANCEL_REASONS.getOrDefault(p.path("reason").asText(""), "");
                String text = "Order cancelled" + (reason.isEmpty() ? "." : " " + reason + ".");
                if (p.path("wasConfirmed").asBoolean(false)) {
                    text += " Any payment is being refunded.";
                }
                String finalText = text;
                forEachSuborder(p, sub -> post(sub, finalText, meta("ORDER_CANCELLED", p, sub)));
            }
            case "ShipmentCreated" -> {
                UUID sub = uuid(p, "suborderId");
                projections.setSuborderStatus(sub, "SHIPPED");
                String carrier = p.path("carrier").asText("");
                String tracking = p.path("trackingNumber").asText("");
                String text = "Shipped" + (carrier.isBlank() ? "" : " with " + carrier)
                        + (tracking.isBlank() ? "." : ". Tracking number: " + tracking);
                ObjectNode meta = meta("SHIPPED", p, sub).put("carrier", carrier).put("trackingNumber", tracking);
                post(sub, text, meta);
            }
            case "SuborderDelivered" -> {
                UUID sub = uuid(p, "suborderId");
                projections.setSuborderStatus(sub, "DELIVERED");
                post(sub, "Delivered. Enjoy your purchase! You can now review the items.", meta("DELIVERED", p, sub));
            }
            default -> {
            }
        }
    }

    private void project(EventEnvelope e, JsonNode p) {
        UUID orderId = uuid(p, "orderId");
        UUID customerId = uuid(p, "customerId");
        String currency = p.path("currency").asText("USD");
        for (JsonNode s : p.path("suborders")) {
            ArrayNode items = json.createArrayNode();
            for (JsonNode it : s.path("items")) {
                items.addObject().put("productId", it.path("productId").asText())
                        .put("productName", it.path("productName").asText())
                        .put("variantName", it.path("variantName").asText())
                        .put("quantity", it.path("quantity").asInt(1));
            }
            String status = "OrderCancelled".equals(e.eventType()) ? "CANCELLED" : s.path("status").asText(p.path("status").asText());
            projections.upsertSuborder(uuid(s, "suborderId"), orderId, customerId, uuid(s, "vendorId"), status,
                    s.path("total").isNumber() ? s.path("total").decimalValue() : BigDecimal.ZERO, currency, items,
                    e.occurredAt());
        }
    }

    private void forEachSuborder(JsonNode p, java.util.function.Consumer<UUID> action) {
        JsonNode subs = p.path("suborders");
        if (subs.isArray() && !subs.isEmpty()) {
            subs.forEach(s -> action.accept(uuid(s, "suborderId")));
        } else {
            projections.suborderIdsOfOrder(uuid(p, "orderId")).forEach(action);
        }
    }

    private void post(UUID suborderId, String text, ObjectNode meta) {
        List<Conversation> related = conversations.bySuborder(suborderId);
        for (Conversation c : related) {
            messages.postSystem(c, text, meta.deepCopy());
        }
    }

    private ObjectNode meta(String event, JsonNode p, UUID suborderId) {
        return json.createObjectNode().put("event", event).put("orderId", p.path("orderId").asText())
                .put("suborderId", suborderId.toString());
    }

    private static UUID uuid(JsonNode n, String field) {
        try {
            return UUID.fromString(n.path(field).asText());
        } catch (IllegalArgumentException e) {
            throw new com.marketflow.platform.events.NonRetryableEventException("Missing or invalid " + field);
        }
    }
}
