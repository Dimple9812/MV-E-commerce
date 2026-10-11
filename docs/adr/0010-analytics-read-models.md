# ADR 0010: Dashboards read from an event-built analytics store

Status: accepted (2026-10)

## Decision

Admin and seller dashboards query analytics-service, which builds fact tables (`order_fact`,
`suborder_fact`, `item_fact`, `payment_fact`, ...) from domain events with the same idempotent consumer
machinery as every other service. Facts are upserts keyed by business id, so redelivered events never
double-count (`AnalyticsIT`). The API reports `lastEventAt` and the UI states that figures can trail live data.
The same service records the audit log (`platform.audit.events`) and a register of every dead-lettered
message across services.

## Consequences

Operational databases never serve analytical queries, and dashboards survive the outage of the service whose
data they show. The price is eventual consistency, which the UI makes explicit.
