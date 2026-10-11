# ADR 0008: Treat unknown payment outcomes as unknown, and reconcile

Status: accepted (2026-10)

## Context

A call to a payment provider can time out after the provider charged the card. Treating the timeout as a
decline would cancel an order the customer paid for; treating it as success would ship unpaid goods. Retrying
blindly could charge twice.

## Decision

- Every provider call carries a stable idempotency key derived from the order id and operation
  (`pay:<orderId>:authorize`), so a retry is
  answered from the provider's record instead of charging again.
- Calls run behind a Resilience4j time limiter (3 s), circuit breaker and bulkhead (`ProviderGateway`). A
  timeout produces `PaymentOutcomeUnknown`, never `PaymentFailed`.
- The saga moves to `RECONCILIATION_REQUIRED` and waits; it does not compensate.
- A reconciliation job in payment-service queries the provider for payments in `UNKNOWN` state (every 5 s
  locally) and publishes the real outcome (`PaymentAuthorized` or `PaymentFailed`), which resumes the saga.
  order-service also re-checks long-waiting sagas (`SAGA_RECONCILIATION_INTERVAL`).
- Asynchronous confirmations arrive by signed webhook (`MarketFlow-Signature: t=<unix>,v1=<HMAC-SHA256>`,
  5-minute tolerance, constant-time comparison); provider event ids are stored so replays are ignored.
- A payment still unknown after the maximum reconciliation attempts stays visible to operators (admin
  operations page, `SagasNeedReconciliation` alert, runbook) instead of being guessed.

## Consequences

- Tests: `lostProviderResponseIsReconciledInsteadOfAssumedFailed`, `slowProviderIsCutByTimeLimiterAndLaterReconciled`,
  `unknownPaymentOutcomeWaitsForReconciliationInsteadOfCancelling`,
  `asyncAuthorisationCompletesOnlyThroughASignedWebhookAndReplaysAreIgnored`.
- The sandbox provider (`SandboxPaymentProvider`) simulates these cases with test tokens (`tok_timeout`,
  `tok_slow`, `tok_async`, ...). A real provider adapter implements the same `PaymentProvider` interface.
