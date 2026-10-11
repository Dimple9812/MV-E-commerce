package com.marketflow.platform.kafka;

import com.marketflow.platform.events.EventCodec;
import com.marketflow.platform.events.EventEnvelope;
import com.marketflow.platform.inbox.InboxGuard;
import com.marketflow.platform.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Consumer template used by every listener:
 * <ol>
 *   <li>parse + validate the envelope (malformed → non-retryable → DLT),</li>
 *   <li>skip event types the consumer does not handle (forward compatibility),</li>
 *   <li>open ONE local transaction containing the inbox insert and the business update,</li>
 *   <li>skip the handler when the inbox says the event was already processed.</li>
 * </ol>
 * Exceptions propagate to the Kafka error handler (retry with backoff, then DLT). The correlation ID of the
 * event is put in the MDC for the duration of the handling.
 */
public class EventListenerSupport {

    private static final Logger log = LoggerFactory.getLogger(EventListenerSupport.class);

    private final EventCodec codec;
    private final InboxGuard inbox;
    private final TransactionTemplate tx;

    public EventListenerSupport(EventCodec codec, InboxGuard inbox, TransactionTemplate tx) {
        this.codec = codec;
        this.inbox = inbox;
        this.tx = tx;
    }

    /** Parses and dispatches without inbox/transaction (for handlers that are naturally idempotent). */
    public void handle(String json, Consumer<EventEnvelope> handler) {
        EventEnvelope envelope = codec.parse(json);
        CorrelationId.set(envelope.correlationId());
        try {
            handler.accept(envelope);
        } finally {
            CorrelationId.clear();
        }
    }

    /**
     * @param consumer     stable consumer name used as the inbox key (normally the consumer group id)
     * @param handledTypes event types this consumer reacts to; others are acknowledged and ignored
     * @param maxVersion   highest schemaVersion this consumer understands
     */
    public void handleOnce(String json, String consumer, Set<String> handledTypes, int maxVersion,
                           Consumer<EventEnvelope> handler) {
        EventEnvelope envelope = codec.parse(json);
        if (!handledTypes.contains(envelope.eventType())) {
            return;
        }
        codec.requireSupportedVersion(envelope, maxVersion);
        CorrelationId.set(envelope.correlationId());
        try {
            tx.executeWithoutResult(status -> {
                if (!inbox.firstDelivery(consumer, envelope.eventId())) {
                    log.info("Duplicate event skipped consumer={} eventType={} eventId={}", consumer,
                            envelope.eventType(), envelope.eventId());
                    return;
                }
                handler.accept(envelope);
            });
        } finally {
            CorrelationId.clear();
        }
    }

    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        return codec.payload(envelope, type);
    }

    public EventCodec codec() {
        return codec;
    }
}
