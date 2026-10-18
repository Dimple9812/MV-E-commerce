-- chat-service owns buyer-seller conversations, their messages, read state, attachments, reports, seller quick
-- replies and response-time statistics, plus local projections of vendors, products and suborders fed by events.

-- One conversation per buyer + vendor + context. A context is a product, a suborder, or neither (a general
-- question to the shop). NULLS NOT DISTINCT (PostgreSQL 15+) makes "no product, no suborder" one context too,
-- instead of letting every NULL count as different.
CREATE TABLE conversation (
    id               UUID PRIMARY KEY,
    buyer_id         UUID         NOT NULL,
    buyer_name       VARCHAR(120) NOT NULL,          -- display name snapshot from the buyer's token
    vendor_id        UUID         NOT NULL,
    product_id       UUID,
    suborder_id      UUID,
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ARCHIVED', 'BLOCKED')),
    last_message_at  TIMESTAMPTZ  NOT NULL,
    last_message_id  BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_conversation_context UNIQUE NULLS NOT DISTINCT (buyer_id, vendor_id, product_id, suborder_id),
    CONSTRAINT ck_conversation_single_context CHECK (product_id IS NULL OR suborder_id IS NULL)
);
-- inbox pages are keyset-paginated on (last_message_at, id)
CREATE INDEX ix_conversation_buyer_inbox ON conversation (buyer_id, last_message_at DESC, id DESC);
CREATE INDEX ix_conversation_vendor_inbox ON conversation (vendor_id, last_message_at DESC, id DESC);
CREATE INDEX ix_conversation_suborder ON conversation (suborder_id) WHERE suborder_id IS NOT NULL;
CREATE INDEX ix_conversation_recent ON conversation (last_message_at DESC, id DESC);

-- Monotonic BIGINT ids: the message timeline is paged with "id < cursor", and unread counts are
-- "id > last_read_message_id", both served by ix_message_timeline.
CREATE TABLE message (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id   UUID          NOT NULL REFERENCES conversation (id),
    sender_id         UUID,                         -- NULL for SYSTEM messages
    sender_role       VARCHAR(10)   NOT NULL CHECK (sender_role IN ('BUYER', 'VENDOR', 'SYSTEM')),
    type              VARCHAR(16)   NOT NULL CHECK (type IN ('TEXT', 'IMAGE', 'PRODUCT_CARD', 'ORDER_CARD', 'SYSTEM')),
    body              VARCHAR(2000),
    attachment_url    VARCHAR(300),
    metadata          JSONB,                        -- card snapshot, attachment info or system event details
    client_message_id UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    edited_at         TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT ck_message_system_sender CHECK ((sender_role = 'SYSTEM') = (sender_id IS NULL)),
    -- idempotent send: a retried frame or request with the same client id never creates a second message
    CONSTRAINT ux_message_client_id UNIQUE (sender_id, client_message_id)
);
CREATE INDEX ix_message_timeline ON message (conversation_id, id DESC);

ALTER TABLE conversation ADD CONSTRAINT fk_conversation_last_message FOREIGN KEY (last_message_id) REFERENCES message (id);

-- Read state per person. The vendor side is a set of users (today the vendor's owner) so that staff accounts
-- can be added without changing the model.
CREATE TABLE conversation_participant (
    conversation_id      UUID        NOT NULL REFERENCES conversation (id),
    user_id              UUID        NOT NULL,
    side                 VARCHAR(6)  NOT NULL CHECK (side IN ('BUYER', 'VENDOR')),
    last_read_message_id BIGINT      NOT NULL DEFAULT 0,
    muted                BOOLEAN     NOT NULL DEFAULT false,
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX ix_participant_user ON conversation_participant (user_id, side);

-- Image attachments are stored in this database (not on a local volume) so any chat-service instance can serve
-- them; AttachmentStorage hides this so object storage can replace it.
CREATE TABLE attachment (
    id              UUID PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES conversation (id),
    uploader_id     UUID        NOT NULL,
    content_type    VARCHAR(20) NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    size_bytes      INT         NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    bytes           BYTEA       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_attachment_conversation ON attachment (conversation_id);

CREATE TABLE conversation_report (
    id              UUID PRIMARY KEY,
    conversation_id UUID          NOT NULL REFERENCES conversation (id),
    reporter_id     UUID          NOT NULL,
    reporter_side   VARCHAR(6)    NOT NULL CHECK (reporter_side IN ('BUYER', 'VENDOR')),
    reason          VARCHAR(30)   NOT NULL CHECK (reason IN ('SPAM', 'SCAM', 'ABUSE', 'OFF_PLATFORM_PAYMENT', 'OTHER')),
    details         VARCHAR(1000),
    status          VARCHAR(10)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution      VARCHAR(20)   CHECK (resolution IN ('DISMISSED', 'CONVERSATION_BLOCKED')),
    resolution_note VARCHAR(1000),
    resolved_by     UUID,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- one open report per person per conversation
CREATE UNIQUE INDEX ux_report_open ON conversation_report (conversation_id, reporter_id) WHERE status = 'OPEN';
CREATE INDEX ix_report_status ON conversation_report (status, created_at DESC);

-- Messages refused by the contact-information policy (kinds only, never the text), shown to admins.
CREATE TABLE content_violation (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES conversation (id),
    user_id         UUID        NOT NULL,
    kinds           VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_violation_conversation ON content_violation (conversation_id, created_at DESC);

CREATE TABLE quick_reply (
    id         UUID PRIMARY KEY,
    vendor_id  UUID          NOT NULL,
    title      VARCHAR(60)   NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    sort_order INT           NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_quick_reply_vendor ON quick_reply (vendor_id, sort_order, created_at);

-- "Usually replies within X hours": median time from a buyer's question to the vendor's first answer (30 days).
CREATE TABLE vendor_response_stats (
    vendor_id                  UUID PRIMARY KEY,
    median_first_reply_seconds INT         NOT NULL,
    sample_size                INT         NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- projections (fed by Kafka, never written by the API) ----------

CREATE TABLE vendor_projection (
    vendor_id      UUID PRIMARY KEY,
    owner_user_id  UUID         NOT NULL UNIQUE,
    business_name  VARCHAR(120) NOT NULL,
    slug           VARCHAR(140) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    vendor_version BIGINT       NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE product_projection (
    product_id      UUID PRIMARY KEY,
    vendor_id       UUID           NOT NULL,
    name            VARCHAR(200)   NOT NULL,
    slug            VARCHAR(220)   NOT NULL,
    image_url       VARCHAR(500),
    min_price       NUMERIC(12, 2),
    currency        VARCHAR(3)     NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    product_version BIGINT         NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_product_projection_vendor ON product_projection (vendor_id);

CREATE TABLE suborder_projection (
    suborder_id  UUID PRIMARY KEY,
    order_id     UUID           NOT NULL,
    customer_id  UUID           NOT NULL,
    vendor_id    UUID           NOT NULL,
    status       VARCHAR(30)    NOT NULL,
    total        NUMERIC(12, 2) NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    item_summary JSONB          NOT NULL,           -- [{productId, productName, variantName, quantity}]
    placed_at    TIMESTAMPTZ    NOT NULL,
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_suborder_projection_order ON suborder_projection (order_id);

-- ---------- platform tables (same layout in every service) ----------

CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID         NOT NULL UNIQUE,
    aggregate_type  VARCHAR(60)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    msg_key         VARCHAR(200) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON outbox_event (id) WHERE status = 'PENDING';

CREATE TABLE processed_event (
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
