package com.marketflow.chat.application;

import com.marketflow.chat.config.ChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Online status and last-seen time in Redis, shared by all instances.
 *
 * <p>{@code chat:presence:<user>} counts the person's open sessions on every instance (several tabs or devices),
 * with a TTL that live instances keep refreshing for their connected users. A crashed instance can therefore
 * not leave someone "online" for longer than the TTL. {@code chat:lastseen:<user>} is written when the last
 * session closes.
 */
@Component
public class Presence {

    private static final Logger log = LoggerFactory.getLogger(Presence.class);
    private static final Duration LAST_SEEN_RETENTION = Duration.ofDays(30);

    public record Status(boolean online, Instant lastSeenAt) {
        static final Status UNKNOWN = new Status(false, null);
    }

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public Presence(StringRedisTemplate redis, ChatProperties props) {
        this.redis = redis;
        this.ttl = props.presenceTtl();
    }

    private static String key(UUID user) {
        return "chat:presence:" + user;
    }

    private static String lastSeenKey(UUID user) {
        return "chat:lastseen:" + user;
    }

    /** @return true if this session made the person go from offline to online */
    public boolean connected(UUID user) {
        try {
            Long n = redis.opsForValue().increment(key(user));
            redis.expire(key(user), ttl);
            return n != null && n == 1;
        } catch (RuntimeException e) {
            log.warn("Presence unavailable: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** @return true if the person has no session left anywhere */
    public boolean disconnected(UUID user, Instant at) {
        try {
            Long n = redis.opsForValue().decrement(key(user));
            if (n == null || n <= 0) {
                redis.delete(key(user));
                redis.opsForValue().set(lastSeenKey(user), at.toString(), LAST_SEEN_RETENTION);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("Presence unavailable: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Called periodically with the users connected to this instance. */
    public void refresh(Collection<UUID> localUsers) {
        try {
            for (UUID u : localUsers) {
                redis.expire(key(u), ttl);
            }
        } catch (RuntimeException e) {
            log.warn("Presence refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    public Status status(UUID user) {
        try {
            String n = redis.opsForValue().get(key(user));
            if (n != null && Long.parseLong(n) > 0) {
                return new Status(true, null);
            }
            String seen = redis.opsForValue().get(lastSeenKey(user));
            return new Status(false, seen == null ? null : Instant.parse(seen));
        } catch (RuntimeException e) {
            return Status.UNKNOWN;
        }
    }

    /** Several accounts (a shop's sellers): online if any is, last seen = the most recent. */
    public Status combined(Collection<UUID> users) {
        boolean online = false;
        Instant last = null;
        for (UUID u : users) {
            Status s = status(u);
            online |= s.online();
            if (s.lastSeenAt() != null && (last == null || s.lastSeenAt().isAfter(last))) {
                last = s.lastSeenAt();
            }
        }
        return new Status(online, online ? null : last);
    }
}
