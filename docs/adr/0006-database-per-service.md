# ADR 0006: Each service owns its database

Status: accepted (2026-10)

## Context

Shared tables are the most common way microservices end up coupled: one team's migration breaks another's
queries, and "just join across" becomes the integration mechanism.

## Decision

One PostgreSQL database per service with its own login role (`infrastructure/postgres/init`). Each role owns
only its database, has `CONNECT` on nothing else and the `public` schema is closed to other roles. Schemas are
managed only by Flyway migrations in the owning service (`ddl-auto=validate`). Data needed from another
service is kept as a **projection** fed by events (for example `vendor_projection` in catalog, inventory and
order; `product_projection` and `variant_projection` in order), carrying a version so stale events are
ignored.

Locally all databases share one PostgreSQL container; in production they can be separate instances without
code changes.

## Consequences

- No service can read or corrupt another's data, even by mistake.
- Projections are eventually consistent: a price change reaches order-service after the event is processed.
  Checkout re-prices from order-service's projection and rejects inactive products, so a stale browser price
  is never charged (`client-supplied prices are ignored` e2e check).
- Reporting across domains is done in analytics-service from events, not with cross-database queries.
