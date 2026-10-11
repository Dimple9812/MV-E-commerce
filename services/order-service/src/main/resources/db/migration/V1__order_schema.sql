-- order-service owns orders, vendor suborders, price snapshots and the checkout saga.

-- ---------- read models fed by other services' events (never by the API) ----------
CREATE TABLE vendor_projection (
    vendor_id       UUID PRIMARY KEY,
    owner_user_id   UUID          NOT NULL UNIQUE,
    business_name   VARCHAR(120)  NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    commission_rate NUMERIC(5, 4) NOT NULL,
    vendor_version  BIGINT        NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE product_projection (
    product_id      UUID PRIMARY KEY,
    vendor_id       UUID         NOT NULL,
    name            VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    currency        VARCHAR(3)   NOT NULL,
    max_per_order   INT          NOT NULL,
    image_url       VARCHAR(500),
    product_version BIGINT       NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE variant_projection (
    variant_id UUID PRIMARY KEY,
    product_id UUID           NOT NULL REFERENCES product_projection (product_id) ON DELETE CASCADE,
    sku        VARCHAR(64)    NOT NULL,
    name       VARCHAR(120)   NOT NULL,
    price      NUMERIC(12, 2) NOT NULL,
    active     BOOLEAN        NOT NULL
);
CREATE INDEX ix_variant_projection_product ON variant_projection (product_id);

-- ---------- orders ----------
CREATE TABLE customer_order (
    id               UUID PRIMARY KEY,
    customer_id      UUID           NOT NULL,
    customer_email   VARCHAR(254),
    status           VARCHAR(30)    NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLATION_REQUESTED',
                                       'CANCELLED', 'REFUND_FAILED', 'PARTIALLY_SHIPPED', 'SHIPPED', 'DELIVERED',
                                       'FAILED')),
    currency         VARCHAR(3)     NOT NULL,
    items_subtotal   NUMERIC(12, 2) NOT NULL CHECK (items_subtotal >= 0),
    discount_total   NUMERIC(12, 2) NOT NULL CHECK (discount_total >= 0),
    shipping_total   NUMERIC(12, 2) NOT NULL CHECK (shipping_total >= 0),
    tax_total        NUMERIC(12, 2) NOT NULL CHECK (tax_total >= 0),
    grand_total      NUMERIC(12, 2) NOT NULL CHECK (grand_total >= 0),
    coupon_code      VARCHAR(40),
    shipping_address JSONB          NOT NULL,
    idempotency_key  VARCHAR(100)   NOT NULL,
    request_hash     VARCHAR(64)    NOT NULL,
    cancel_reason    VARCHAR(300),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    confirmed_at     TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    version          BIGINT         NOT NULL DEFAULT 0,
    -- checkout idempotency: the same customer + key can only ever create one order
    CONSTRAINT ux_order_idempotency UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX ix_order_customer ON customer_order (customer_id, created_at DESC);
CREATE INDEX ix_order_status ON customer_order (status, created_at DESC);

CREATE TABLE suborder (
    id                UUID PRIMARY KEY,
    order_id          UUID           NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    vendor_id         UUID           NOT NULL,
    vendor_name       VARCHAR(120)   NOT NULL,
    status            VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED',
                                        'CANCELLED')),
    items_subtotal    NUMERIC(12, 2) NOT NULL,
    discount_amount   NUMERIC(12, 2) NOT NULL,
    shipping_amount   NUMERIC(12, 2) NOT NULL,
    tax_amount        NUMERIC(12, 2) NOT NULL,
    total             NUMERIC(12, 2) NOT NULL,
    commission_rate   NUMERIC(5, 4)  NOT NULL,
    commission_amount NUMERIC(12, 2) NOT NULL,
    vendor_net_amount NUMERIC(12, 2) NOT NULL,
    carrier           VARCHAR(60),
    tracking_number   VARCHAR(100),
    shipped_at        TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ux_suborder_vendor UNIQUE (order_id, vendor_id)
);
CREATE INDEX ix_suborder_vendor ON suborder (vendor_id, status);

-- price/name snapshots at order time: later catalog changes never alter a placed order
CREATE TABLE order_item (
    id              UUID PRIMARY KEY,
    order_id        UUID           NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    suborder_id     UUID           NOT NULL REFERENCES suborder (id) ON DELETE CASCADE,
    product_id      UUID           NOT NULL,
    variant_id      UUID           NOT NULL,
    sku             VARCHAR(64)    NOT NULL,
    product_name    VARCHAR(200)   NOT NULL,
    variant_name    VARCHAR(120)   NOT NULL,
    image_url       VARCHAR(500),
    quantity        INT            NOT NULL CHECK (quantity > 0),
    unit_price      NUMERIC(12, 2) NOT NULL CHECK (unit_price > 0),
    line_subtotal   NUMERIC(12, 2) NOT NULL,
    discount_amount NUMERIC(12, 2) NOT NULL,
    line_total      NUMERIC(12, 2) NOT NULL
);
CREATE INDEX ix_item_order ON order_item (order_id);
CREATE INDEX ix_item_suborder ON order_item (suborder_id);

CREATE TABLE order_status_history (
    id          BIGSERIAL PRIMARY KEY,
    order_id    UUID        NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    suborder_id UUID,
    from_status VARCHAR(30),
    to_status   VARCHAR(30) NOT NULL,
    reason      VARCHAR(300),
    actor_id    UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_order_history ON order_status_history (order_id, occurred_at);

-- ---------- checkout saga ----------
CREATE TABLE checkout_saga (
    order_id              UUID PRIMARY KEY REFERENCES customer_order (id) ON DELETE CASCADE,
    state                 VARCHAR(40)  NOT NULL,
    attempt               INT          NOT NULL DEFAULT 0,
    deadline_at           TIMESTAMPTZ  NOT NULL,
    payment_token         VARCHAR(100) NOT NULL,   -- opaque provider token (no card data)
    pending_compensations VARCHAR(100) NOT NULL DEFAULT '',
    compensation_reason   VARCHAR(300),
    last_error            VARCHAR(500),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_saga_deadline ON checkout_saga (deadline_at)
    WHERE state NOT IN ('CONFIRMED', 'CANCELLED', 'FAILED');

CREATE TABLE saga_transition (
    id          BIGSERIAL PRIMARY KEY,
    order_id    UUID        NOT NULL REFERENCES checkout_saga (order_id) ON DELETE CASCADE,
    from_state  VARCHAR(40),
    to_state    VARCHAR(40) NOT NULL,
    trigger     VARCHAR(60) NOT NULL,
    event_id    UUID,
    detail      VARCHAR(300),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_saga_transition_order ON saga_transition (order_id, id);

-- cancellation-after-confirmation workflow (refund + restock)
CREATE TABLE cancellation (
    order_id      UUID PRIMARY KEY REFERENCES customer_order (id) ON DELETE CASCADE,
    requested_by  UUID         NOT NULL,
    reason        VARCHAR(300) NOT NULL,
    refund_done   BOOLEAN      NOT NULL DEFAULT false,
    restock_done  BOOLEAN      NOT NULL DEFAULT false,
    failed_reason VARCHAR(300),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
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
