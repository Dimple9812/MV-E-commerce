package com.marketflow.chat.ws;

import com.marketflow.chat.domain.ChatActor;
import com.marketflow.platform.security.Role;

import java.security.Principal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Identity of a STOMP session, fixed at CONNECT from the verified JWT. {@link #getName()} is the user id, which
 * is what Spring's user destinations ({@code /user/queue/...}) resolve against.
 */
public record ChatPrincipal(UUID userId, Set<Role> roles, String fullName, Instant expiresAt) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }

    public ChatActor actor() {
        return new ChatActor(userId, roles, fullName);
    }

    /** Same 60-second clock-skew allowance as the JWT decoder that accepted the token on CONNECT. */
    static final java.time.Duration CLOCK_SKEW = java.time.Duration.ofSeconds(60);

    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt.plus(CLOCK_SKEW));
    }
}
