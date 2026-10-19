package com.marketflow.chat.domain;

import com.marketflow.platform.security.Role;

import java.util.Set;
import java.util.UUID;

/**
 * The person acting, always taken from a verified JWT (REST: the security context; STOMP: the session principal
 * set on CONNECT). Nothing in a request body or frame can change who this is.
 */
public record ChatActor(UUID userId, Set<Role> roles, String name) {

    public boolean admin() {
        return roles.contains(Role.ADMIN);
    }

    /** Short public form used in conversations: "Ada M." rather than the full name. */
    public String displayName() {
        if (name == null || name.isBlank()) {
            return "Customer";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
