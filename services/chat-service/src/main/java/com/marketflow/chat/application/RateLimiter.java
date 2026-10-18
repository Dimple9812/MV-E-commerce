package com.marketflow.chat.application;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Per-user limits for chat frames, which the gateway cannot see (it only sees the WebSocket handshake).
 *
 * <ul>
 *   <li>messages: fixed window, at most {@code messages} per {@code window} per user across all instances
 *       (the counter lives in Redis), including messages sent over REST;</li>
 *   <li>typing: at most one "is typing" event per user and conversation per {@code typingInterval}.</li>
 * </ul>
 * If the counter store is unavailable the limiter lets the request through (fail-open, like the gateway's
 * RedisRateLimiter): chat should keep working during a Redis blip, and Postgres is not affected by the extra load.
 */
public class RateLimiter {

    /** Shared counter store; {@link RedisWindowCounter} in production, an in-memory fake in unit tests. */
    public interface WindowCounter {
        /** Increments the counter for {@code key}, creating it with {@code ttl} if absent; returns the new value. */
        long increment(String key, Duration ttl);

        /** Sets the key only if it does not exist yet; returns true if it was set. */
        boolean setIfAbsent(String key, Duration ttl);
    }

    private final WindowCounter counter;
    private final Clock clock;
    private final int messages;
    private final Duration window;
    private final Duration typingInterval;

    public RateLimiter(WindowCounter counter, Clock clock, int messages, Duration window, Duration typingInterval) {
        this.counter = counter;
        this.clock = clock;
        this.messages = messages;
        this.window = window;
        this.typingInterval = typingInterval;
    }

    public boolean tryAcquireMessage(UUID userId) {
        long bucket = clock.millis() / window.toMillis();
        try {
            return counter.increment("chat:rl:msg:" + userId + ":" + bucket, window.multipliedBy(2)) <= messages;
        } catch (RuntimeException storeDown) {
            return true;
        }
    }

    public boolean tryTyping(UUID userId, UUID conversationId) {
        try {
            return counter.setIfAbsent("chat:rl:typing:" + userId + ":" + conversationId, typingInterval);
        } catch (RuntimeException storeDown) {
            return true;
        }
    }

    public Duration window() {
        return window;
    }
}
