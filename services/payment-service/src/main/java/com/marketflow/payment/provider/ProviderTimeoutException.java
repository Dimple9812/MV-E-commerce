package com.marketflow.payment.provider;

/** The provider did not answer in time: the operation MAY have succeeded. Must be reconciled, never assumed failed. */
public class ProviderTimeoutException extends RuntimeException {
    public ProviderTimeoutException(String message) {
        super(message);
    }

    public ProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
