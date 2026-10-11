-- review-service owns reviews, rating aggregates and the purchase projection used for verified-purchase checks.

-- Built from order.events: who bought which product. A refunded/cancelled order removes eligibility.
CREATE TABLE purchase (
    customer_id  UUID        NOT NULL,
    product_id   UUID        NOT NULL,
    order_id     UUID        NOT NULL,
    purchased_at TIMESTAMPTZ NOT NULL,
    cancelled    BOOLEAN     NOT NULL DEFAULT false,
    PRIMARY KEY (customer_id, product_id, order_id)
);
CREATE INDEX ix_purchase_order ON purchase (order_id);

CREATE TABLE review (
    id                UUID PRIMARY KEY,
    product_id        UUID          NOT NULL,
    customer_id       UUID          NOT NULL,
    author_name       VARCHAR(120)  NOT NULL,
    rating            SMALLINT      NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title             VARCHAR(120)  NOT NULL,
    body              VARCHAR(4000) NOT NULL,
    status            VARCHAR(12)   NOT NULL CHECK (status IN ('PUBLISHED', 'HIDDEN', 'DELETED')),
    verified_purchase BOOLEAN       NOT NULL,
    moderation_note   VARCHAR(300),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    -- one review per customer per product (duplicate-review prevention)
    CONSTRAINT ux_review_product_customer UNIQUE (product_id, customer_id)
);
CREATE INDEX ix_review_product ON review (product_id, created_at DESC) WHERE status = 'PUBLISHED';
CREATE INDEX ix_review_status ON review (status, created_at DESC);

CREATE TABLE product_rating (
    product_id   UUID PRIMARY KEY,
    review_count INT           NOT NULL DEFAULT 0,
    average      NUMERIC(3, 2) NOT NULL DEFAULT 0,
    histogram    JSONB         NOT NULL DEFAULT '{}'::jsonb,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
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
