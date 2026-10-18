package com.marketflow.chat.ws;

import com.marketflow.chat.api.ChatDtos.PresenceEvent;
import com.marketflow.chat.application.ChatFanout;
import com.marketflow.chat.application.Presence;
import com.marketflow.chat.persistence.ConversationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks this instance's STOMP sessions: presence (online / last seen), and closing sessions whose access
 * token has expired. The client refreshes its token every 12 minutes and reconnects with the new one, so an
 * expired session is normally a client that went to sleep; it gets an ERROR frame, which closes the socket.
 */
@Component
public class SessionTracker {

    private static final Logger log = LoggerFactory.getLogger(SessionTracker.class);

    private final Map<String, ChatPrincipal> sessions = new ConcurrentHashMap<>();
    private final Presence presence;
    private final ChatFanout fanout;
    private final ConversationRepository conversations;
    private final MessageChannel clientOutbound;
    private final Clock clock;

    public SessionTracker(Presence presence, ChatFanout fanout, ConversationRepository conversations,
                          @org.springframework.beans.factory.annotation.Qualifier("clientOutboundChannel")
                          MessageChannel clientOutbound, Clock clock, MeterRegistry meters) {
        this.presence = presence;
        this.fanout = fanout;
        this.conversations = conversations;
        this.clientOutbound = clientOutbound;
        this.clock = clock;
        Gauge.builder("chat.sessions", sessions, Map::size).description("Open STOMP sessions on this instance")
                .register(meters);
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor a = StompHeaderAccessor.wrap(event.getMessage());
        if (!(event.getUser() instanceof ChatPrincipal p) || a.getSessionId() == null) {
            return;
        }
        if (sessions.put(a.getSessionId(), p) == null && presence.connected(p.userId())) {
            announce(p.userId(), true, null);
        }
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        ChatPrincipal p = sessions.remove(event.getSessionId());
        if (p == null) {
            return; // never fully connected, or already handled (disconnect events can repeat)
        }
        Instant now = clock.instant();
        if (presence.disconnected(p.userId(), now)) {
            announce(p.userId(), false, now);
        }
    }

    private void announce(UUID userId, boolean online, Instant lastSeen) {
        try {
            List<UUID> watchers = conversations.recentCounterparts(userId, 500);
            fanout.toUsers(watchers, "/queue/presence", new PresenceEvent(userId, online, lastSeen));
        } catch (RuntimeException e) {
            log.warn("Presence announcement failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Keeps this instance's users marked online and ends sessions with expired tokens. */
    @Scheduled(fixedDelayString = "${marketflow.chat.session-sweep-interval:30s}")
    public void sweep() {
        Set<UUID> users = sessions.values().stream().map(ChatPrincipal::userId).collect(Collectors.toSet());
        presence.refresh(users);
        Instant now = clock.instant();
        sessions.forEach((sessionId, p) -> {
            if (p.expired(now)) {
                StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
                error.setSessionId(sessionId);
                error.setMessage("token_expired");
                error.setLeaveMutable(true);
                clientOutbound.send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
            }
        });
    }

    public int openSessions() {
        return sessions.size();
    }
}
