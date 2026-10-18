package com.marketflow.chat.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Message(long id, UUID conversationId, UUID senderId, String senderRole, MessageType type, String body,
                      String attachmentUrl, JsonNode metadata, UUID clientMessageId, Instant createdAt,
                      Instant editedAt, Instant deletedAt) {

    public boolean deleted() {
        return deletedAt != null;
    }

    /**
     * Only the sender may change a message, only within the edit window, never after deletion, and only text can
     * be edited (cards and images can be deleted but not rewritten).
     */
    public boolean changeableBy(UUID userId, Instant now, Duration window, boolean edit) {
        if (senderId == null || !senderId.equals(userId) || deleted()) {
            return false;
        }
        if (edit && type != MessageType.TEXT) {
            return false;
        }
        return !now.isAfter(createdAt.plus(window));
    }
}
