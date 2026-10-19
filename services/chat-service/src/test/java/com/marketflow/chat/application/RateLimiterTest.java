package com.marketflow.chat.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    /** In-memory stand-in for Redis (TTL ignored: windows are separate keys). */
    static class FakeCounter implements RateLimiter.WindowCounter {
        final Map<String, Long> values = new HashMap<>();
        boolean down;

        @Override
        public long increment(String key, Duration ttl) {
            if (down) {
                throw new IllegalStateException("redis down");
            }
            return values.merge(key, 1L, Long::sum);
        }

        @Override
        public boolean setIfAbsent(String key, Duration ttl) {
            if (down) {
                throw new IllegalStateException("redis down");
            }
            return values.putIfAbsent(key, 1L) == null;
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final FakeCounter counter = new FakeCounter();
    final MutableClock clock = new MutableClock();
    final RateLimiter limiter = new RateLimiter(counter, clock, 3, Duration.ofSeconds(10), Duration.ofSeconds(2));

    @Test
    void allowsTheLimitPerWindowThenRefusesUntilTheNextWindow() {
        UUID user = UUID.randomUUID();
        assertThat(limiter.tryAcquireMessage(user)).isTrue();
        assertThat(limiter.tryAcquireMessage(user)).isTrue();
        assertThat(limiter.tryAcquireMessage(user)).isTrue();
        assertThat(limiter.tryAcquireMessage(user)).isFalse();
        clock.now = clock.now.plusSeconds(10);
        assertThat(limiter.tryAcquireMessage(user)).isTrue();
    }

    @Test
    void limitsArePerUser() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquireMessage(a);
        }
        assertThat(limiter.tryAcquireMessage(a)).isFalse();
        assertThat(limiter.tryAcquireMessage(b)).isTrue();
    }

    @Test
    void typingIsThrottledPerConversation() {
        UUID user = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        assertThat(limiter.tryTyping(user, c1)).isTrue();
        assertThat(limiter.tryTyping(user, c1)).isFalse();
        assertThat(limiter.tryTyping(user, c2)).isTrue();
    }

    @Test
    void failsOpenWhenTheCounterStoreIsDown() {
        counter.down = true;
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquireMessage(user)).isTrue();
        }
        assertThat(limiter.tryTyping(user, UUID.randomUUID())).isTrue();
    }
}
