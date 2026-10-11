package com.marketflow.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire format shared by every MarketFlow Kafka message. The canonical definition is the JSON Schema in
 * {@code contracts/events/envelope.schema.json}; this record is a convenience binding, not the contract.
 *
 * <p>Consumers must ignore unknown fields (forward compatibility) and reject envelopes whose
 * {@code schemaVersion} major number is greater than what they understand.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String producer,
        JsonNode payload) {

    public void validate() {
        if (eventId == null || eventType == null || eventType.isBlank() || aggregateId == null
                || occurredAt == null || payload == null || schemaVersion < 1) {
            throw new EventDeserializationException("Envelope is missing required fields: " + eventType);
        }
    }
}
