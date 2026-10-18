package com.marketflow.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketflow.platform.events.EventEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns domain events into notification drafts. Pure (no I/O): contact details are resolved by the caller.
 * Each draft carries a dedupe key derived from the event, so re-processing an event (retry topic, redelivery)
 * can never create - or send - the same notification twice.
 */
final class NotificationTemplates {

    private NotificationTemplates() {
    }

    enum Audience { CUSTOMER, VENDOR }

    /** One chat notification per conversation and recipient per window. */
    static final long CHAT_NOTIFICATION_WINDOW_SECONDS = 15 * 60;

    record Draft(Audience audience, UUID userId, UUID vendorId, String channel, String template, String subject,
                 String body, String link, String dedupeKey) {
    }

    static List<Draft> plan(EventEnvelope e) {
        JsonNode p = e.payload();
        List<Draft> out = new ArrayList<>();
        switch (e.eventType()) {
            case "UserRegistered" -> {
                UUID user = uuid(p, "userId");
                out.add(new Draft(Audience.CUSTOMER, user, null, "EMAIL", "welcome", "Welcome to MarketFlow",
                        "Hi " + p.path("fullName").asText("there") + ",\n\nyour account is ready. Happy shopping!",
                        "/", "welcome:" + user));
            }
            case "VendorApproved", "VendorRejected", "VendorSuspended", "VendorReinstated" -> {
                UUID vendor = uuid(p, "vendorId");
                UUID owner = uuid(p, "ownerUserId");
                String status = switch (e.eventType()) {
                    case "VendorApproved" -> "approved - you can now publish products";
                    case "VendorRejected" -> "rejected: " + p.path("reason").asText("no reason given");
                    case "VendorSuspended" -> "suspended: " + p.path("reason").asText("contact support");
                    default -> "reinstated - welcome back";
                };
                String subject = "Your seller account was " + status.split(" ")[0];
                both(out, Audience.VENDOR, owner, vendor, "vendor_status", subject,
                        p.path("businessName").asText() + " has been " + status + ".", "/vendor", e.eventId());
            }
            case "OrderConfirmed" -> {
                UUID order = uuid(p, "orderId");
                UUID customer = uuid(p, "customerId");
                both(out, Audience.CUSTOMER, customer, null, "order_confirmed", "Order confirmed",
                        "Thank you! Your order " + shortId(order) + " totalling " + p.path("totalAmount").asText()
                                + " " + p.path("currency").asText() + " is confirmed and with "
                                + p.path("suborders").size() + " seller(s).", "/account/orders/" + order, e.eventId());
                for (JsonNode s : p.path("suborders")) {
                    UUID vendor = uuid(s, "vendorId");
                    both(out, Audience.VENDOR, null, vendor, "new_suborder", "New order to fulfil",
                            "You have a new order (" + s.path("items").size() + " line(s), "
                                    + s.path("total").asText() + " " + p.path("currency").asText() + ").",
                            "/vendor/orders", e.eventId() + ":" + vendor);
                }
            }
            case "OrderCancelled" -> {
                UUID order = uuid(p, "orderId");
                String reason = switch (p.path("reason").asText("")) {
                    case "OUT_OF_STOCK" -> "an item went out of stock before we could reserve it";
                    case "PAYMENT_DECLINED" -> "the payment was declined";
                    case "CAPTURE_FAILED" -> "the payment could not be completed";
                    default -> p.path("reason").asText("it was cancelled");
                };
                both(out, Audience.CUSTOMER, uuid(p, "customerId"), null, "order_cancelled", "Order cancelled",
                        "Your order " + shortId(order) + " was cancelled because " + reason + "."
                                + (p.path("wasConfirmed").asBoolean() ? " A full refund is on its way." : " You were not charged."),
                        "/account/orders/" + order, e.eventId());
            }
            case "ShipmentCreated" -> {
                UUID order = uuid(p, "orderId");
                both(out, Audience.CUSTOMER, uuid(p, "customerId"), null, "shipment_created", "Your order has shipped",
                        "Part of order " + shortId(order) + " is on its way with " + p.path("carrier").asText()
                                + " (tracking " + p.path("trackingNumber").asText() + ").",
                        "/account/orders/" + order, e.eventId());
            }
            case "OrderDelivered" -> {
                UUID order = uuid(p, "orderId");
                out.add(new Draft(Audience.CUSTOMER, uuid(p, "customerId"), null, "IN_APP", "order_delivered",
                        "Order delivered", "Order " + shortId(order) + " was delivered. Enjoy - and leave a review!",
                        "/account/orders/" + order, "in_app:" + e.eventId()));
            }
            case "ChatMessageSent" -> {
                // In-app only, and at most one per conversation and recipient per 15 minutes: a lively chat must
                // not flood the bell. The text is never in the event, so the notification only says who wrote.
                UUID conversation = uuid(p, "conversationId");
                String sender = p.path("senderName").asText("Someone");
                String what = switch (p.path("messageType").asText("TEXT")) {
                    case "IMAGE" -> "sent you a photo";
                    case "PRODUCT_CARD" -> "shared a product with you";
                    case "ORDER_CARD" -> "sent you an order";
                    default -> "sent you a message";
                };
                boolean toShop = "VENDOR".equals(p.path("recipientSide").asText());
                String link = (toShop ? "/vendor/messages/" : "/account/messages/") + conversation;
                long bucket = java.time.Instant.parse(p.path("sentAt").asText(e.occurredAt().toString()))
                        .getEpochSecond() / CHAT_NOTIFICATION_WINDOW_SECONDS;
                for (JsonNode r : p.path("recipientUserIds")) {
                    UUID recipient = UUID.fromString(r.asText());
                    out.add(new Draft(Audience.CUSTOMER, recipient, null, "IN_APP", "chat_message",
                            "New message from " + sender, sender + " " + what + ".", link,
                            "in_app:chat_message:" + conversation + ":" + recipient + ":" + bucket));
                }
            }
            case "LowStockDetected" -> {
                UUID vendor = uuid(p, "vendorId");
                both(out, Audience.VENDOR, null, vendor, "low_stock", "Low stock: " + p.path("sku").asText(),
                        p.path("productName").asText() + " (" + p.path("sku").asText() + ") has only "
                                + p.path("available").asInt() + " unit(s) left.", "/vendor/inventory", e.eventId());
            }
            default -> {
            }
        }
        return out;
    }

    private static void both(List<Draft> out, Audience a, UUID user, UUID vendor, String template, String subject,
                             String body, String link, Object key) {
        out.add(new Draft(a, user, vendor, "EMAIL", template, subject, body, link, "email:" + template + ":" + key));
        out.add(new Draft(a, user, vendor, "IN_APP", template, subject, body, link, "in_app:" + template + ":" + key));
    }

    private static UUID uuid(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return v == null ? null : UUID.fromString(v);
    }

    private static String shortId(UUID id) {
        return "#" + id.toString().substring(0, 8).toUpperCase();
    }
}
