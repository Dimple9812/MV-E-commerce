package com.marketflow.platform.security;

import com.marketflow.platform.web.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Trusted identity extracted from a verified JWT. Services must derive the acting user exclusively from this
 * object; user IDs or roles supplied in URLs or request bodies are never trusted.
 */
public record AuthenticatedUser(UUID userId, String email, Set<Role> roles) {

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return has(Role.ADMIN);
    }

    public static AuthenticatedUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return fromJwt(token.getToken());
        }
        throw ApiException.unauthorized("Authentication required");
    }

    public static AuthenticatedUser fromJwt(Jwt jwt) {
        UUID id;
        try {
            id = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw ApiException.unauthorized("Invalid token subject");
        }
        Object raw = jwt.getClaims().get(JwtRoles.CLAIM);
        Collection<?> list = raw instanceof Collection<?> c ? c : List.of();
        Set<Role> roles = list.stream().map(Object::toString).map(Role::parse)
                .filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        return new AuthenticatedUser(id, jwt.getClaimAsString("email"), roles);
    }
}
