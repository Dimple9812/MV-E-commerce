-- inventory-service is the single source of truth for stock.
--
-- Accounting model (per inventory_item):
--   available + reserved = physically on hand and not yet shipped
--   available + reserved + sold = total units ever received (sum of RECEIVE/ADJUST movements)
-- Every change to available/reserved/sold writes a stock_movement row with the exact deltas, so at any time
--   available = SUM(delta_available), reserved = SUM(delta_reserved), sold = SUM(delta_sold)
-- and the CHECK constraints make negative stock (overselling) impossible at the database level.

CREATE TABLE vendor_projection (
    vendor_id      UUID PRIMARY KEY,
    owner_user_id  UUID        NOT NULL UNIQUE,
    status         VARCHAR(20) NOT NULL,
    vendor_version BIGINT      NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory_item (
    id                  UUID PRIMARY KEY,
    variant_id          UUID         NOT NULL UNIQUE,
    product_id          UUID         NOT NULL,
    vendor_id           UUID         NOT NULL,
    sku                 VARCHAR(64)  NOT NULL,
    product_name        VARCHAR(200) NOT NULL,
    variant_name        VARCHAR(120) NOT NULL,
    available           INT          NOT NULL DEFAULT 0 CHECK (available >= 0),
    reserved            INT          NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    sold                INT          NOT NULL DEFAULT 0 CHECK (sold >= 0),
    low_stock_threshold INT          NOT NULL DEFAULT 5 CHECK (low_stock_threshold >= 0),
    product_version     BIGINT       NOT NULL DEFAULT 0,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_inventory_vendor ON inventory_item (vendor_id, sku);
CREATE INDEX ix_inventory_low_stock ON inventory_item (vendor_id) WHERE available <= low_stock_threshold;

-- One reservation per order: the UNIQUE(order_id) constraint makes a repeated ReserveInventory command a no-op.
CREATE TABLE reservation (
    id             UUID PRIMARY KEY,
    order_id       UUID         NOT NULL UNIQUE,
    status         VARCHAR(20)  NOT NULL
        CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'FAILED', 'RETURNED')),
    failure_reason VARCHAR(300),
    expires_at     TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_reservation_expiry ON reservation (expires_at) WHERE status = 'RESERVED';

CREATE TABLE reservation_line (
    reservation_id    UUID NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    inventory_item_id UUID NOT NULL REFERENCES inventory_item (id),
    variant_id        UUID NOT NULL,
    quantity          INT  NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (reservation_id, variant_id)
);

CREATE TABLE stock_movement (
    id                BIGSERIAL PRIMARY KEY,
    inventory_item_id UUID         NOT NULL REFERENCES inventory_item (id),
    movement_type     VARCHAR(20)  NOT NULL
        CHECK (movement_type IN ('ADJUST', 'RESERVE', 'RELEASE', 'EXPIRE', 'CONFIRM', 'CONFIRM_LATE', 'RETURN')),
    delta_available   INT          NOT NULL,
    delta_reserved    INT          NOT NULL,
    delta_sold        INT          NOT NULL,
    reference_type    VARCHAR(30)  NOT NULL,
    reference_id      VARCHAR(64)  NOT NULL,
    actor_id          UUID,
    reason            VARCHAR(300),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_movement_item ON stock_movement (inventory_item_id, created_at DESC);

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
