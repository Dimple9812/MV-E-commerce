package com.marketflow.payment.provider;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Payment provider boundary (Stripe/Adyen/... would be adapters). Every mutating call carries an idempotency key
 * so the provider never executes the same operation twice, however often we retry.
 *
 * <p>Implementations throw {@link ProviderTimeoutException} when the outcome is UNKNOWN (request may or may not
 * have been executed) and {@link ProviderUnavailableException} when the request certainly did not reach the provider.
 */
public interface PaymentProvider {

    String name();

    enum Status { AUTHORIZED, DECLINED, PENDING, CAPTURED, CAPTURE_FAILED, VOIDED, REFUNDED, PARTIALLY_REFUNDED,
        FAILED, NOT_FOUND }

    record Result(Status status, String providerPaymentId, String code) {
    }

    Result authorize(String merchantReference, BigDecimal amount, String currency, String token, String idempotencyKey);

    Result capture(String providerPaymentId, BigDecimal amount, String idempotencyKey);

    Result voidAuthorization(String providerPaymentId, String idempotencyKey);

    /** @return refund outcome; providerPaymentId field carries the provider refund ID */
    Result refund(String providerPaymentId, BigDecimal amount, String idempotencyKey);

    /** Looks up what the provider recorded for our merchant reference (the order ID). */
    Optional<Result> query(String merchantReference);
}
