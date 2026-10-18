package com.marketflow.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record Conversation(UUID id, UUID buyerId, String buyerName, UUID vendorId, UUID productId, UUID suborderId,
                           ConversationStatus status, Instant lastMessageAt, Long lastMessageId, Instant createdAt) {

    public boolean writable() {
        return status == ConversationStatus.OPEN;
    }
}
