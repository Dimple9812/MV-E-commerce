package com.marketflow.payment.provider;

/** The request was NOT sent (circuit open, bulkhead full): safe to retry later. */
public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
