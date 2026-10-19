package com.marketflow.chat.ws;

import com.marketflow.platform.security.AuthenticatedUser;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.time.Clock;

/**
 * Authenticates STOMP sessions. Browsers cannot put an Authorization header on a WebSocket handshake, so the
 * handshake itself is anonymous and the access token travels in the STOMP CONNECT frame instead (never in the URL,
 * where it would end up in access logs).
 *
 * <ul>
 *   <li>CONNECT: the token is verified with the same decoder as the REST API (RS256 signature from the JWKS,
 *       issuer, audience, expiry). The session principal becomes the token subject; nothing a client sends later
 *       can change it.</li>
 *   <li>SEND / SUBSCRIBE: require an authenticated, unexpired session. Clients may only send to {@code /app/...}
 *       and only subscribe to their own user queues ({@code /user/queue/...}); the broker's raw destinations are
 *       unreachable, so nobody can listen to someone else's queue.</li>
 * </ul>
 * A rejected frame is dropped and the session receives a STOMP ERROR frame (whose {@code message} header names
 * the reason), which also closes the WebSocket. The ERROR frame is sent explicitly instead of throwing: with
 * ordered processing of client frames ({@code setPreserveReceiveOrder}), an exception thrown here would only be
 * logged and the connection would stay open.
 */
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    /** A frame that must not be processed; the code is sent to the client in the ERROR frame. */
    static final class Rejected extends RuntimeException {
        Rejected(String code) {
            super(code, null, false, false);
        }
    }

    private final JwtDecoder decoder;
    private final Clock clock;
    private final ObjectProvider<MessageChannel> clientOutbound;

    public JwtChannelInterceptor(JwtDecoder decoder, Clock clock,
                                 @Qualifier("clientOutboundChannel") ObjectProvider<MessageChannel> clientOutbound) {
        this.decoder = decoder;
        this.clock = clock;
        this.clientOutbound = clientOutbound;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (a == null || a.getCommand() == null) {
            return message;
        }
        try {
            return check(message, a);
        } catch (Rejected r) {
            reject(a.getSessionId(), r.getMessage());
            return null;
        }
    }

    private void reject(String sessionId, String code) {
        if (sessionId == null) {
            return;
        }
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setSessionId(sessionId);
        error.setMessage(code);
        error.setLeaveMutable(true);
        clientOutbound.getObject().send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
    }

    private Message<?> check(Message<?> message, StompHeaderAccessor a) {
        StompCommand command = a.getCommand();
        switch (command) {
            case CONNECT, STOMP -> a.setUser(authenticate(a));
            case SEND -> {
                requireSession(a);
                String destination = a.getDestination();
                if (destination == null || !destination.startsWith("/app/")) {
                    throw new Rejected("forbidden_destination");
                }
            }
            case SUBSCRIBE -> {
                requireSession(a);
                String destination = a.getDestination();
                if (destination == null || !destination.startsWith("/user/queue/")) {
                    throw new Rejected("forbidden_destination");
                }
            }
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK, NACK and heartbeats need no checks
            }
        }
        return message;
    }

    private ChatPrincipal authenticate(StompHeaderAccessor a) {
        String header = a.getFirstNativeHeader("Authorization");
        if (header == null) {
            header = a.getFirstNativeHeader("authorization");
        }
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new Rejected("unauthorized");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(header.substring(7).trim());
        } catch (JwtException e) {
            throw new Rejected("unauthorized");
        }
        AuthenticatedUser user;
        try {
            user = AuthenticatedUser.fromJwt(jwt);
        } catch (RuntimeException e) {
            throw new Rejected("unauthorized");
        }
        return new ChatPrincipal(user.userId(), user.roles(), jwt.getClaimAsString("name"), jwt.getExpiresAt());
    }

    private void requireSession(StompHeaderAccessor a) {
        Principal p = a.getUser();
        if (!(p instanceof ChatPrincipal cp)) {
            throw new Rejected("unauthorized");
        }
        if (cp.expired(clock.instant())) {
            throw new Rejected("token_expired");
        }
    }
}
