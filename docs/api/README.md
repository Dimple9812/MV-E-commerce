# API documentation

- **OpenAPI 3 specifications**, exported from the running services on 2026-10-11:
  [`contracts/api/openapi-<service>.json`](../../contracts/api). Regenerate by starting the stack and running
  `curl -s localhost:<port>/v3/api-docs` for each service (ports in the main README).
- **Swagger UI** at `http://localhost:<port>/swagger-ui.html` for each service.
- **Through the gateway** every path is under `http://localhost:8080/api/v1/...`; the gateway adds
  authentication, rate limiting and the correlation id but does not change request or response bodies.
- **Errors** are RFC 9457 `application/problem+json`:

```json
{
  "type": "https://marketflow.dev/problems/invalid_idempotency_key",
  "title": "Bad Request",
  "status": 400,
  "detail": "Idempotency-Key header is required (8-100 characters of A-Z a-z 0-9 - _ : .)",
  "instance": "/api/v1/orders/checkout",
  "code": "invalid_idempotency_key",
  "correlationId": "fc644d1d-c94e-4f1e-a064-ee06e37bc47e"
}
```

  Validation failures add `errors: [{ "field": "...", "message": "..." }]`. The `code` is stable and meant
  for clients; `detail` is for humans.
- **Events** (asynchronous API): [`contracts/events`](../../contracts/events/README.md).
