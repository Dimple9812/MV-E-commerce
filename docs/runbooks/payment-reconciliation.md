# Payment outcome unknown / reconciliation

## Background

When a provider call times out (3 s time limiter) the outcome is unknown: the card may or may not have been
charged. payment-service records the payment as `UNKNOWN` and publishes `PaymentOutcomeUnknown`; the saga moves
to `RECONCILIATION_REQUIRED` and **does not cancel** (ADR 0008).

Two loops settle it automatically:

1. payment-service's reconciliation job (every 5 s) asks the provider about `PENDING`/`UNKNOWN` payments and
   about in-flight calls older than 60 s, using the same idempotency key, and publishes the real outcome.
2. order-service re-polls sagas in `RECONCILIATION_REQUIRED` at each deadline, up to the maximum attempts.

If the outcome is still unknown after the maximum attempts, the saga becomes `FAILED` and the order `FAILED`
with reason `PAYMENT_UNRESOLVED`. Should the provider later report the payment authorised or captured,
order-service automatically voids the authorisation or refunds the capture (`LatePaymentAuthorized` /
`LatePaymentCaptured` notes in the saga history).

## Signals

- Alert `SagasNeedReconciliation` (`saga_instances{state="RECONCILIATION_REQUIRED"} > 0` for 5 min).
- Admin > Operations > sagas "Reconciliation required"; Admin > Payments filter "Awaiting reconciliation".
- Metrics: `payment_reconciliations_total{result=...}`, `payment_outcomes_total{result="unknown"}`.

## Procedure

1. Check the provider is reachable: `payment_provider_calls_seconds_count{outcome="timeout"}` rate, the
   `provider` circuit breaker state (`resilience4j_circuitbreaker_state{name="provider"}` in payment-service), provider status page.
2. If the provider is down, wait: the loops resume when it returns. Nothing needs to be done per order.
3. For an order in `FAILED / PAYMENT_UNRESOLVED`:
   - Look up the payment: `GET /api/v1/payments/by-order/<orderId>` (admin token) and its attempts:
     `GET /api/v1/admin/payments/<paymentId>/attempts`.
   - Check the provider dashboard with the merchant reference (the order id).
   - Charged and captured, and the late event did not arrive: refund from Admin > Orders > order > Issue refund
     (idempotent per dialog, bounded by the captured amount).
   - Only authorised: the authorisation expires at the provider; a late `PaymentAuthorized` event is voided
     automatically.
4. Tell the customer (support), referencing the order id. Do not re-run checkout for them with the same
   idempotency key: it returns the failed order by design.

## Sandbox reproduction

Pay with **"Card on a slow network"** (`tok_timeout`) at checkout: the order waits in reconciliation and
settles a few seconds later. `tok_timeout_decline` settles as declined; `tok_slow` exceeds the time limiter and
is reconciled.
