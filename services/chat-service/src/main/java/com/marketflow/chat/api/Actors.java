package com.marketflow.chat.api;

import com.marketflow.chat.domain.ChatActor;
import com.marketflow.platform.security.AuthenticatedUser;
import com.marketflow.platform.web.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The REST caller as a {@link ChatActor}, from the verified JWT in the security context. */
final class Actors {

    private Actors() {
    }

    static ChatActor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            AuthenticatedUser u = AuthenticatedUser.fromJwt(token.getToken());
            return new ChatActor(u.userId(), u.roles(), token.getToken().getClaimAsString("name"));
        }
        throw ApiException.unauthorized("Authentication required");
    }
}
