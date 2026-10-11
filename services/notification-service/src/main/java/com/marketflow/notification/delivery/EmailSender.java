package com.marketflow.notification.delivery;

/** E-mail transport boundary (SMTP via Mailpit locally; a real relay/SES in production). */
public interface EmailSender {

    void send(String to, String subject, String body);

    /** Temporary problem (SMTP down, throttled): retry with backoff. */
    class TransientDeliveryException extends RuntimeException {
        public TransientDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Will never succeed (invalid/bounced address): do not retry, go to the DLT. */
    class PermanentDeliveryException extends RuntimeException {
        public PermanentDeliveryException(String message) {
            super(message);
        }
    }
}
