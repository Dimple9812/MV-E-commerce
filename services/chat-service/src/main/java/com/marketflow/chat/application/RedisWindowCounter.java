package com.marketflow.chat.application;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** {@link RateLimiter.WindowCounter} on Redis: INCR + EXPIRE on first use, SET NX PX. */
public class RedisWindowCounter implements RateLimiter.WindowCounter {

    private final StringRedisTemplate redis;

    public RedisWindowCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) {
            redis.expire(key, ttl);
        }
        return n == null ? 0 : n;
    }

    @Override
    public boolean setIfAbsent(String key, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
    }
}
