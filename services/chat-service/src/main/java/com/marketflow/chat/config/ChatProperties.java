package com.marketflow.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param allowedOrigins     browser origins allowed to open the WebSocket directly (requests without Origin pass)
 * @param maxMessageLength   characters per text message, after trimming
 * @param editWindow         how long after sending a message can be edited or deleted
 * @param attachmentMaxBytes upper bound for an uploaded image
 * @param presenceTtl        an "online" marker expires unless a live instance refreshes it within this time
 * @param heartbeat          STOMP heartbeat in both directions
 * @param fanoutChannel      Redis pub/sub channel that carries deliveries between instances
 * @param blockedLinkHosts   link hosts treated as off-platform payment or contact channels
 */
@ConfigurationProperties(prefix = "marketflow.chat")
public record ChatProperties(
        List<String> allowedOrigins,
        int maxMessageLength,
        Duration editWindow,
        RateLimit rateLimit,
        int attachmentMaxBytes,
        Duration presenceTtl,
        Duration heartbeat,
        String fanoutChannel,
        List<String> blockedLinkHosts) {

    /**
     * @param messages       messages a user may send per {@code window}
     * @param typingInterval at most one typing event per conversation and user in this interval
     */
    public record RateLimit(int messages, Duration window, Duration typingInterval) {
    }

    public ChatProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        blockedLinkHosts = blockedLinkHosts == null ? List.of() : blockedLinkHosts.stream().map(String::trim)
                .map(String::toLowerCase).filter(s -> !s.isEmpty()).toList();
        if (maxMessageLength <= 0) {
            maxMessageLength = 2000;
        }
        if (editWindow == null) {
            editWindow = Duration.ofMinutes(10);
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(20, Duration.ofSeconds(10), Duration.ofSeconds(2));
        }
        if (attachmentMaxBytes <= 0) {
            attachmentMaxBytes = 5 * 1024 * 1024;
        }
        if (presenceTtl == null) {
            presenceTtl = Duration.ofSeconds(90);
        }
        if (heartbeat == null) {
            heartbeat = Duration.ofSeconds(10);
        }
        if (fanoutChannel == null || fanoutChannel.isBlank()) {
            fanoutChannel = "chat:fanout";
        }
    }
}
