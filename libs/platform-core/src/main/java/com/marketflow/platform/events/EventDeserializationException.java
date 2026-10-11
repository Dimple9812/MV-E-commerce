package com.marketflow.platform.events;

/** Malformed or incompatible message. Never retried: routed straight to the dead-letter topic. */
public class EventDeserializationException extends NonRetryableEventException {
    public EventDeserializationException(String message) {
        super(message);
    }

    public EventDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
