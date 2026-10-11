package com.marketflow.catalog.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Redis cache-aside for public catalog reads.
 *
 * <ul>
 *   <li><b>Read:</b> GET; on miss, one caller acquires a short {@code SET NX PX} lock and loads from PostgreSQL;
 *       concurrent callers briefly poll for the value instead of stampeding the database. If the lock holder is
 *       slow, waiters fall back to the database after ~300ms (correctness never depends on the lock).</li>
 *   <li><b>Negative caching:</b> "not found" is cached for 30s to blunt cache-penetration with random IDs.</li>
 *   <li><b>TTL jitter:</b> ±10% so keys written together do not expire together.</li>
 *   <li><b>Invalidation:</b> keys are deleted <i>after the DB transaction commits</i>, and deleted again ~1s later
 *       ("delayed double delete") to evict a stale value a concurrent reader may have written between the commit
 *       and the first delete.</li>
 *   <li><b>Failure:</b> any Redis error degrades to a direct database read; the cache is never a source of truth.</li>
 * </ul>
 */
@Component
public class CacheAside {

    private static final Logger log = LoggerFactory.getLogger(CacheAside.class);
    private static final String NULL_MARKER = "__null__";
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final TaskScheduler scheduler;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public CacheAside(StringRedisTemplate redis, ObjectMapper mapper, TaskScheduler scheduler, MeterRegistry meters) {
        this.redis = redis;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.hits = Counter.builder("catalog.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("catalog.cache").tag("result", "miss").register(meters);
        this.errors = Counter.builder("catalog.cache").tag("result", "error").register(meters);
    }

    public <T> Optional<T> get(String key, Duration ttl, Class<T> type, Supplier<Optional<T>> loader) {
        return get(key, ttl, mapper.getTypeFactory().constructType(type), loader);
    }

    public <T> Optional<T> get(String key, Duration ttl, JavaType type, Supplier<Optional<T>> loader) {
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                hits.increment();
                return NULL_MARKER.equals(cached) ? Optional.empty() : Optional.of(mapper.readValue(cached, type));
            }
            misses.increment();
            String lockKey = key + ":lock";
            String token = UUID.randomUUID().toString();
            Boolean locked = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
            if (Boolean.TRUE.equals(locked)) {
                try {
                    Optional<T> loaded = loader.get();
                    write(key, ttl, loaded);
                    return loaded;
                } finally {
                    if (token.equals(redis.opsForValue().get(lockKey))) {
                        redis.delete(lockKey);
                    }
                }
            }
            for (int i = 0; i < 6; i++) { // someone else is loading: wait up to ~300ms
                Thread.sleep(50);
                String v = redis.opsForValue().get(key);
                if (v != null) {
                    hits.increment();
                    return NULL_MARKER.equals(v) ? Optional.empty() : Optional.of(mapper.readValue(v, type));
                }
            }
            return loader.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loader.get();
        } catch (Exception e) {
            errors.increment();
            log.warn("Cache unavailable for key {} ({}); reading from database", key, e.getClass().getSimpleName());
            return loader.get();
        }
    }

    private <T> void write(String key, Duration ttl, Optional<T> value) throws Exception {
        if (value.isEmpty()) {
            redis.opsForValue().set(key, NULL_MARKER, NEGATIVE_TTL);
            return;
        }
        long base = ttl.toMillis();
        long jitter = (long) (base * 0.1 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        redis.opsForValue().set(key, mapper.writeValueAsString(value.get()), Duration.ofMillis(base + jitter));
    }

    /** Deletes keys after the surrounding transaction commits (immediately if there is none), then again 1s later. */
    public void evictAfterCommit(String... keys) {
        Runnable evict = () -> delete(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                    scheduler.schedule(evict, Instant.now().plusSeconds(1));
                }
            });
        } else {
            evict.run();
            scheduler.schedule(evict, Instant.now().plusSeconds(1));
        }
    }

    private void delete(String... keys) {
        try {
            redis.delete(java.util.Arrays.asList(keys));
        } catch (Exception e) {
            errors.increment();
            log.warn("Cache eviction failed for {} ({}); entries will expire by TTL", (Object) keys,
                    e.getClass().getSimpleName());
        }
    }
}
