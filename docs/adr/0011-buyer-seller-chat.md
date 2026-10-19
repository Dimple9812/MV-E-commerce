# ADR 0011: Buyer-seller chat as its own service, STOMP over WebSocket, Redis fan-out between instances

Status: accepted (2026-10)

## Context

Buyers need to ask a shop about a product before buying and about an order after it (gift wrap, a late
parcel). Messages must arrive live, survive a restart, stay inside the marketplace (no phone numbers or
payment links that take the sale off-platform), and be reviewable by administrators in a dispute. Order status
changes belong in the same thread.

## Decision

- **chat-service** owns conversations, messages, read state, attachments, reports and quick replies in its own
  `chat_db`. It knows shops, products and suborders only through projections fed by `vendor.events`,
  `catalog.product.events` and `order.events` (ADR 0006), so starting a conversation never calls another service.
  One conversation per buyer, shop and context (product, suborder or none) is enforced by a unique constraint
  (`NULLS NOT DISTINCT`).
- **Transport: STOMP over plain WebSocket** at `/ws/chat`, through the gateway (`chat-ws` route without circuit
  breaker or time limiter, which would cut a long-lived exchange). No SockJS: its HTTP fallbacks need sticky
  sessions, and every supported browser has WebSocket.
- **Authentication on CONNECT.** Browsers cannot add an `Authorization` header to a WebSocket handshake, and a
  token in the URL ends up in access logs, so the access token travels in the STOMP CONNECT frame and
  `JwtChannelInterceptor` verifies it with the same decoder as REST (RS256, issuer, audience, expiry). The session
  principal is the token subject; senders are never taken from a payload. Clients may only SEND to `/app/**` and
  SUBSCRIBE to their own `/user/queue/**`. An expired token gets an ERROR frame (`token_expired`) and the client
  reconnects with a fresh one.
- **Authorisation** (`ChatAccess`, pure and unit-tested): the buyer and the shop's owner read and write; an
  administrator reads any conversation and never writes; everyone else gets 404. Vendor staff accounts do not
  exist yet (one owner per vendor), so the participant table is already multi-user for when they do.
- **Fan-out between instances.** Each instance runs Spring's simple broker for its own sessions. After the
  transaction commits, a delivery `{recipients, destination, payload}` is published on the Redis channel
  `chat:fanout`; every instance pushes it to the recipients connected to it. PostgreSQL stays the source of
  truth: pub/sub is fire-and-forget, so clients resynchronise with `GET .../messages?after=` on every reconnect.
  If Redis is down, local recipients are still served.
- **Rules on the write path**, in this order: idempotency (`UNIQUE (sender_id, client_message_id)`, a retry
  returns the original), a Redis fixed-window rate limit per user (20 messages / 10 s, typing at most every 2 s),
  then content: plain text only (rendered as text, never HTML), 2,000 characters, no control characters, and the
  contact policy (phone numbers, e-mail addresses including spelled-out forms, UPI ids and payment or messenger
  links are refused with `422 contact_info_not_allowed` and counted for administrators). Edits and deletes by the
  sender within 10 minutes; deletes are soft.
- **Events.** `ChatMessageSent` (no message body: events never carry user content) and `ConversationReported`
  go to `chat.events` through the outbox. notification-service turns `ChatMessageSent` into at most one in-app
  notification per conversation and recipient every 15 minutes. Order events become SYSTEM messages in existing
  conversations about that suborder, once each thanks to the inbox.
- **Presence and reply time.** Connection counts per user in Redis with a TTL refreshed by heartbeats (a crashed
  instance cannot leave anyone "online"); a shop's typical reply time is the median of its first replies over
  30 days, recomputed periodically.

## Alternatives considered

- **STOMP broker relay (RabbitMQ/ActiveMQ)** instead of Redis pub/sub: a full broker with persistent queues and
  native multi-instance support, but a new piece of infrastructure to run for a feature whose durability already
  comes from PostgreSQL. Redis is already in the stack.
- **Kafka for live delivery**: durable and ordered, but consumer-group semantics make "every instance sees every
  delivery" awkward (one group per instance), and its latency is tuned for throughput. Kafka is used for what it is
  good at here: integration events.
- **Sticky sessions per conversation**: avoids the fan-out but couples routing to conversation ids and breaks
  when a user has tabs on two instances.
- **Polling**: simplest, but seconds of latency and constant load for idle inboxes.

## Consequences

Any instance can serve any user and instances scale horizontally; delivery latency is one PostgreSQL commit plus
one Redis hop. Missed live deliveries are possible during an instance restart and are repaired by the resync, so
clients must implement it (the React app does). Per-connection memory is dominated by the servlet container's
WebSocket buffers (about 93 KB per idle session measured, see `load-tests/README.md`), which sets the number of
connections an instance can hold for a given heap.
