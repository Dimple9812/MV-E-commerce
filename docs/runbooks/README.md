# Runbooks

| Runbook | Use when |
|---|---|
| [dlt-replay.md](dlt-replay.md) | `DeadLetters` alert, entries on the admin Operations page, a consumer stopped making progress on a message |
| [payment-reconciliation.md](payment-reconciliation.md) | `SagasNeedReconciliation` alert, orders failed with `PAYMENT_UNRESOLVED`, payments stuck in `UNKNOWN` |
| [outbox-failed.md](outbox-failed.md) | `OutboxEventsParked` or `OutboxBacklog` alert |
| [stuck-checkout.md](stuck-checkout.md) | a customer's order stays "pending", saga state not terminal after minutes |
| [local-stack.md](local-stack.md) | the Compose stack does not start or a service is unhealthy |

All commands assume the Docker Compose stack; adapt the host names for Kubernetes (`kubectl exec`).
