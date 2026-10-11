package com.marketflow.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payloads published by identity-service. Contract: contracts/events/identity.user.events/*.schema.json */
public final class IdentityEvents {

    public static final String USER_TOPIC = "identity.user.events";
    public static final String AGGREGATE = "User";

    private IdentityEvents() {
    }

    public record UserRegistered(UUID userId, String email, String fullName, Instant registeredAt) {
    }

    public record UserRolesChanged(UUID userId, List<String> roles, String reason) {
    }

    public record UserStatusChanged(UUID userId, String status) {
    }

    /** Reset links themselves are never put on Kafka; identity mails them directly. */
    public record PasswordChanged(UUID userId, Instant changedAt) {
    }
}
