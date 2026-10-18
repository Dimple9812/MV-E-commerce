package com.marketflow.chat.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bindings of the chat.events contracts (contracts/events/schemas/chat.events.*). Payloads never contain message
 * text: the notification consumer only needs to know that someone wrote, and to whom.
 */
public final class ChatEvents {

    public static final String TOPIC = "chat.events";
    public static final String AGGREGATE = "Conversation";

    private ChatEvents() {
    }

    public record ChatMessageSent(UUID conversationId, long messageId, UUID vendorId, UUID buyerId, String senderRole,
                                  String senderName, List<UUID> recipientUserIds, String recipientSide,
                                  String messageType, UUID productId, UUID suborderId, Instant sentAt) {
    }

    public record ConversationReported(UUID reportId, UUID conversationId, UUID vendorId, String reporterSide,
                                       String reason) {
    }
}
