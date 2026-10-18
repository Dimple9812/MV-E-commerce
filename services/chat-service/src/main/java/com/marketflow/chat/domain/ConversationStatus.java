package com.marketflow.chat.domain;

public enum ConversationStatus {
    /** normal */
    OPEN,
    /** kept for history; reserved for a later "archive" feature */
    ARCHIVED,
    /** closed by an administrator after a report; readable, not writable */
    BLOCKED
}
