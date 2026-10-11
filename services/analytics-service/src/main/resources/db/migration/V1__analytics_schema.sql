-- analytics-service: read models built ONLY from events (it never queries other services' databases).
-- Everything here is eventually consistent; dashboards show the time of the last processed event.

CREATE TABLE vendor_dim (
    vendor_id      UUID PRIMARY KEY,
    owner_user_id  UUID         NOT NULL,
    business_name  VARCHAR(120) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    vendor_version BIGINT       NOT NULL
);

CREATE TABLE order_fact (
    order_id      UUID PRIMARY KEY,
    customer_id   UUID           NOT NULL,
    status        VARCHAR(30)    NOT NULL,
    total         NUMERIC(12, 2) NOT NULL,
    currency      VARCHAR(3)     NOT NULL,
    vendor_count  INT            NOT NULL,
    item_count    INT            NOT NULL,
    cancel_reason VARCHAR(100),
    created_at    TIMESTAMPTZ    NOT NULL,
    confirmed_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ
);
CREATE INDEX ix_order_fact_created ON order_fact (created_at);
CREATE INDEX ix_order_fact_status ON order_fact (status);

CREATE TABLE suborder_fact (
    suborder_id  UUID PRIMARY KEY,
    order_id     UUID           NOT NULL,
    vendor_id    UUID           NOT NULL,
    vendor_name  VARCHAR(120)   NOT NULL,
    status       VARCHAR(20)    NOT NULL,
    total        NUMERIC(12, 2) NOT NULL,
    commission   NUMERIC(12, 2) NOT NULL,
    vendor_net   NUMERIC(12, 2) NOT NULL,
    confirmed_at TIMESTAMPTZ    NOT NULL
);
CREATE INDEX ix_suborder_fact_vendor ON suborder_fact (vendor_id, confirmed_at);

CREATE TABLE item_fact (
    suborder_id  UUID           NOT NULL,
    variant_id   UUID           NOT NULL,
    product_id   UUID           NOT NULL,
    vendor_id    UUID           NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    sku          VARCHAR(64)    NOT NULL,
    quantity     INT            NOT NULL,
    revenue      NUMERIC(12, 2) NOT NULL,
    confirmed_at TIMESTAMPTZ    NOT NULL,
    cancelled    BOOLEAN        NOT NULL DEFAULT false,
    PRIMARY KEY (suborder_id, variant_id)
);
CREATE INDEX ix_item_fact_vendor ON item_fact (vendor_id);

CREATE TABLE payment_fact (
    order_id        UUID PRIMARY KEY,
    status          VARCHAR(24)    NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL,
    refunded_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    failure_code    VARCHAR(60),
    updated_at      TIMESTAMPTZ    NOT NULL
);

CREATE TABLE inventory_alert (
    variant_id   UUID PRIMARY KEY,
    vendor_id    UUID         NOT NULL,
    product_id   UUID         NOT NULL,
    sku          VARCHAR(64)  NOT NULL,
    product_name VARCHAR(200),
    available    INT          NOT NULL,
    threshold    INT          NOT NULL,
    detected_at  TIMESTAMPTZ  NOT NULL,
    resolved_at  TIMESTAMPTZ
);
CREATE INDEX ix_inventory_alert_open ON inventory_alert (vendor_id) WHERE resolved_at IS NULL;

-- Platform-wide audit log assembled from every service's AuditRecorded events.
CREATE TABLE audit_log (
    event_id      UUID PRIMARY KEY,
    service       VARCHAR(60)  NOT NULL,
    actor_id      UUID,
    actor_role    VARCHAR(20),
    action        VARCHAR(80)  NOT NULL,
    resource_type VARCHAR(60)  NOT NULL,
    resource_id   VARCHAR(64)  NOT NULL,
    outcome       VARCHAR(20)  NOT NULL,
    details       JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    occurred_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_audit_time ON audit_log (occurred_at DESC);
CREATE INDEX ix_audit_action ON audit_log (action, occurred_at DESC);
CREATE INDEX ix_audit_actor ON audit_log (actor_id, occurred_at DESC);

-- Records of messages that ended up in any dead-letter topic (operational failure indicator).
CREATE TABLE dead_letter (
    id          BIGSERIAL PRIMARY KEY,
    topic       VARCHAR(200) NOT NULL,
    partition_no INT         NOT NULL,
    offset_no   BIGINT       NOT NULL,
    consumer    VARCHAR(100),
    exception   VARCHAR(300),
    event_type  VARCHAR(100),
    event_id    VARCHAR(64),
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_dead_letter_position UNIQUE (topic, partition_no, offset_no)
);
CREATE INDEX ix_dead_letter_time ON dead_letter (received_at DESC);

CREATE TABLE consumer_checkpoint (
    stream        VARCHAR(60) PRIMARY KEY,
    last_event_at TIMESTAMPTZ NOT NULL,
    events_total  BIGINT      NOT NULL DEFAULT 0
);

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
