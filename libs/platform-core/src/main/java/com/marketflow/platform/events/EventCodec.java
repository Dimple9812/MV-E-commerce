package com.marketflow.platform.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.platform.web.CorrelationId;

import java.time.Instant;
import java.util.UUID;

/** Serializes/deserializes envelopes and binds payloads to service-local DTOs. */
public class EventCodec {

    private final ObjectMapper objectMapper;
    private final String producer;

    public EventCodec(ObjectMapper objectMapper, String producer) {
        this.objectMapper = objectMapper;
        this.producer = producer;
    }

    public EventEnvelope newEnvelope(String eventType, int schemaVersion, String aggregateType,
                                     String aggregateId, Object payload, String causationId) {
        return new EventEnvelope(UUID.randomUUID(), eventType, schemaVersion, aggregateType, aggregateId,
                Instant.now(), CorrelationId.currentOrNew(), causationId, producer,
                objectMapper.valueToTree(payload));
    }

    public String toJson(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.eventType(), e);
        }
    }

    public EventEnvelope parse(String json) {
        try {
            EventEnvelope envelope = objectMapper.readValue(json, EventEnvelope.class);
            envelope.validate();
            return envelope;
        } catch (JsonProcessingException e) {
            throw new EventDeserializationException("Malformed event JSON", e);
        }
    }

    /**
     * Binds the payload. Unknown properties are ignored (ObjectMapper configured by Boot with
     * FAIL_ON_UNKNOWN_PROPERTIES=false), so producers may add optional fields without breaking consumers.
     */
    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new EventDeserializationException("Payload does not match " + type.getSimpleName()
                    + " for " + envelope.eventType(), e);
        }
    }

    /** Rejects envelopes from a newer, incompatible major schema version. */
    public void requireSupportedVersion(EventEnvelope envelope, int maxSupportedVersion) {
        if (envelope.schemaVersion() > maxSupportedVersion) {
            throw new EventDeserializationException("Unsupported schemaVersion " + envelope.schemaVersion()
                    + " for " + envelope.eventType() + " (max " + maxSupportedVersion + ")");
        }
    }

    public JsonNode tree(Object value) {
        return objectMapper.valueToTree(value);
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
