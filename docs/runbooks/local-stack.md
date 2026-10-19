# Local stack troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `required variable ... is missing a value` | no `.env` | `make env` (or `cp .env.example .env` and fill it) |
| postgres healthy but services fail with `password authentication failed` | the volume was initialised with other passwords | passwords are set only on first start: `docker compose down -v` (deletes data) or `ALTER ROLE` them |
| services restart in a loop, `Flyway ... checksum mismatch` | a migration file changed after it ran | never edit applied migrations; in development reset with `docker compose down -v` |
| kafka unhealthy after a hard stop | stale KRaft metadata | `docker compose down`; if it persists `docker volume rm marketflow_kafka-data` (topics are recreated, history lost) |
| everyone is signed out after `identity-service` restarts | no `JWT_PRIVATE_KEY_PEM`, so a new dev key is generated | expected in development; set a key in `.env` to keep sessions |
| 503 "service is temporarily unavailable" from the gateway | downstream not ready or breaker open | `docker compose ps`; the breaker retries after 10 s |
| 429 Too Many Requests | gateway rate limit (20 req/s per user or IP) | expected; for load tests raise `RATE_LIMIT_RPS` / `RATE_LIMIT_BURST` |
| no e-mails | they go to Mailpit | open http://localhost:8025 |
| Grafana panels empty | Prometheus has no samples yet | wait one scrape (15 s); check http://localhost:9090/targets |
| containers killed with exit code 137 | Docker Desktop memory limit | give Docker at least 8 GB; the stack runs 19 containers |
| `add CHAT_DB_PASSWORD to .env` after pulling the chat feature | `.env` predates chat-service | add `CHAT_DB_PASSWORD=<random>` to `.env`; postgres creates `chat_db` on the existing volume at its next start (`infrastructure/postgres/ensure-service-database.sh`), no reset needed |
| chat shows "Reconnecting…" and never connects | the WebSocket upgrade is not passed through, or chat-service is down | `docker compose ps chat-service`; behind your own proxy forward `Upgrade`/`Connection` for `/ws/` and allow reads longer than 10 s (heartbeats) |
| a message shows "Not sent: contact details…" | chat-service refuses phone numbers, e-mail addresses and payment links | by design (keeps sales on the platform); rephrase without them |
| live chat works on one tab but a second instance's users get nothing | Redis unreachable from chat-service (fan-out between instances) | check `chat_fanout_failed_total` on `/actuator/prometheus`; local users are still served, others resync on reconnect |

**When to delete volumes**: only when you want a clean database (`docker compose down -v`). Plain
`docker compose down` keeps orders, users and Kafka history. Deleting only `kafka-data` while keeping
databases leaves projections consistent but drops the event history used to rebuild read models.
