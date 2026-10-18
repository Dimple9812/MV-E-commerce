package com.marketflow.chat.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.chat.config.ChatProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Delivers socket events to users wherever they are connected.
 *
 * <p>Every chat-service instance only knows its own WebSocket sessions. A delivery is therefore published to one
 * Redis pub/sub channel; every instance (this one included) receives it and pushes it to those recipients that
 * are connected locally ({@link LocalDelivery}). Publishing happens <b>after commit</b>, so a client never sees a
 * message that a rollback later removes.
 *
 * <p>Pub/sub is fire-and-forget: an instance that is restarting misses deliveries. That is acceptable because
 * PostgreSQL is the source of truth: clients resynchronise with {@code GET .../messages?after=} whenever they
 * (re)connect. If Redis itself is unavailable, the delivery still reaches recipients on this instance.
 */
@Component
public class ChatFanout {

    private static final Logger log = LoggerFactory.getLogger(ChatFanout.class);

    /** Wire format on the Redis channel. */
    public record Delivery(List<UUID> recipients, String destination, JsonNode payload) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final LocalDelivery local;
    private final String channel;
    private final Counter published;
    private final Counter failed;

    public ChatFanout(StringRedisTemplate redis, ObjectMapper json, LocalDelivery local, ChatProperties props,
                      MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.local = local;
        this.channel = props.fanoutChannel();
        this.published = meters.counter("chat.fanout.published");
        this.failed = meters.counter("chat.fanout.failed");
    }

    public void toUsers(Collection<UUID> recipients, String destination, Object payload) {
        if (recipients.isEmpty()) {
            return;
        }
        Delivery d = new Delivery(List.copyOf(recipients), destination, json.valueToTree(payload));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(d);
                }
            });
        } else {
            publish(d);
        }
    }

    void publish(Delivery d) {
        try {
            redis.convertAndSend(channel, json.writeValueAsString(d));
            published.increment();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            failed.increment();
            log.warn("Redis fan-out unavailable ({}); delivering to local sessions only", e.getClass().getSimpleName());
            local.deliver(d);
        }
    }

    public String channel() {
        return channel;
    }
}
