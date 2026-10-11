package com.marketflow.platform.outbox;

import com.marketflow.platform.events.EventCodec;
import com.marketflow.platform.events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends an event to the service-local {@code outbox_event} table.
 *
 * <p>{@link Propagation#MANDATORY} guarantees the row is written in the SAME local transaction as the business
 * state change that produced it: either both commit or neither does. Publishing to Kafka happens later in
 * {@link OutboxPublisher}. Writing to the DB and then calling Kafka directly would lose the event whenever the
 * process crashes (or Kafka is unavailable) between the commit and the send.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final EventCodec codec;

    public OutboxWriter(JdbcTemplate jdbc, EventCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope append(String topic, String eventType, int schemaVersion, String aggregateType,
                                String aggregateId, Object payload) {
        return append(topic, eventType, schemaVersion, aggregateType, aggregateId, payload, null);
    }

    /**
     * @param causationId the eventId of the message that caused this one (null for user-initiated actions)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope append(String topic, String eventType, int schemaVersion, String aggregateType,
                                String aggregateId, Object payload, String causationId) {
        EventEnvelope envelope = codec.newEnvelope(eventType, schemaVersion, aggregateType, aggregateId,
                payload, causationId);
        jdbc.update("""
                INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type, topic, msg_key, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, envelope.eventId(), aggregateType, aggregateId, eventType, topic, aggregateId,
                codec.toJson(envelope));
        return envelope;
    }
}
