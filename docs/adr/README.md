# Architecture decision records

| ADR | Decision |
|---|---|
| [0001](0001-microservices-with-clear-ownership.md) | Domain microservices with one owner per bounded context |
| [0002](0002-kafka-as-the-event-backbone.md) | Kafka for domain events and saga commands |
| [0003](0003-transactional-outbox.md) | Transactional outbox for every published event |
| [0004](0004-orchestrated-checkout-saga.md) | Orchestrated, persisted checkout saga with compensations |
| [0005](0005-idempotent-consumers.md) | Idempotent consumers and idempotent APIs |
| [0006](0006-database-per-service.md) | Database per service, projections instead of joins |
| [0007](0007-preventing-overselling.md) | Conditional updates and a stock ledger against overselling |
| [0008](0008-payment-uncertainty-and-reconciliation.md) | Unknown payment outcomes are reconciled, never guessed |
| [0009](0009-gateway-authentication.md) | RS256 JWTs, rotated refresh tokens, authorisation at two layers |
| [0010](0010-analytics-read-models.md) | Event-built analytics store for dashboards and audit |
