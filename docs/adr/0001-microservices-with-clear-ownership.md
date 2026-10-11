# ADR 0001: Split the marketplace into domain microservices

Status: accepted (2026-10)

## Context

A multi-vendor marketplace has domains with different change rates, consistency needs and load profiles:
the catalog is read-heavy and cacheable, checkout and payments need strict correctness, notifications and
analytics tolerate delay. The project also exists to demonstrate distributed-systems practice (sagas,
outbox, idempotent consumers) that a monolith would not need.

## Decision

Nine domain services plus an API gateway, each a Spring Boot application with its own database, owning one
bounded context:

| Service | Owns |
|---|---|
| identity | users, credentials, roles, refresh tokens, addresses; issues JWTs |
| vendor | seller applications, status lifecycle, commission, earnings ledger, payouts |
| catalog | categories, products, variants, images, storefront search |
| inventory | stock counters, reservations, the stock movement ledger |
| order | orders, vendor suborders, pricing, the checkout saga |
| payment | payments, provider calls, refunds, webhooks, reconciliation |
| notification | e-mail and in-app notifications, delivery retries |
| review | purchases eligible for review, reviews, ratings |
| analytics | read models for dashboards, audit log, dead-letter register |

Shared code lives in `libs/platform-core` and is limited to infrastructure (event envelope, outbox,
inbox/dedupe, error handling, security wiring, problem details). It contains no domain logic, so services do
not couple through shared classes; cross-service contracts are the JSON schemas in `contracts/`.

## Consequences

- Each service can be deployed, scaled and reasoned about alone; a failing review service does not block
  checkout.
- Cross-service workflows become asynchronous and eventually consistent, which forces explicit handling of
  duplicates, ordering and partial failure (ADRs 0003-0005).
- Operational cost is real: ten JVMs, ten databases, distributed tracing to follow one request. For a small
  team building a real product a modular monolith would be the cheaper starting point; this trade-off is
  accepted here deliberately.

## Alternatives considered

- **Modular monolith** with one database and module boundaries: simpler and transactional, but it would not
  exercise the patterns this project is meant to show.
- **Finer-grained services** (separate cart, pricing, search, shipping): more network hops for no
  additional learning value at this scale.
