# ADR 0009: Stateless JWT access tokens, rotated refresh tokens, enforced at gateway and services

Status: accepted (2026-10)

## Decision

- identity-service signs **RS256** access tokens (15 min) and publishes the public key as JWKS. No other service
  holds a signing secret; every service and the gateway validate signature, issuer, audience and expiry.
- Refresh tokens (7 days) are random, stored only as SHA-256 hashes, sent as an `HttpOnly; SameSite=Strict` cookie
  scoped to `/api/v1/auth` (plus `Secure` when `REFRESH_COOKIE_SECURE=true`, mandatory behind HTTPS and set in
  the Helm chart; local development runs over plain HTTP), and **rotated** on every use. Reusing an already-rotated
  token revokes the whole token family (stolen-token detection). Password change or reset revokes all.
- Authorisation is enforced twice: coarse role rules at the gateway (`/api/v1/admin/**` ADMIN,
  `/api/v1/vendor/**` VENDOR) and fine-grained rules in each service (`@PreAuthorize` plus ownership checks:
  a customer's order, a vendor's product). A request that bypasses the gateway gets the same answer.
- The browser keeps the access token in memory only; the refresh endpoint requires a custom header, which
  together with SameSite=Strict blocks cross-site request forgery.

## Consequences

Access tokens cannot be revoked before expiry (15-minute exposure window); role changes reach a session at
the next refresh, which the frontend triggers when it expects one (after seller approval).
