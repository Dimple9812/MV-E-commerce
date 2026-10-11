package com.marketflow.platform.events;

/**
 * A permanent failure while handling an event (business-rule violation, unsupported schema, bad data).
 * The Kafka error handler classifies this as non-retryable and publishes the record to its DLT immediately.
 */
public class NonRetryableEventException extends RuntimeException {
    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
