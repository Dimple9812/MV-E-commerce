-- vendor-service owns vendor profiles, the approval workflow, commission configuration and the earnings ledger.

CREATE TABLE vendor (
    id               UUID PRIMARY KEY,
    owner_user_id    UUID          NOT NULL UNIQUE,      -- one vendor per user account
    business_name    VARCHAR(120)  NOT NULL,
    slug             VARCHAR(140)  NOT NULL UNIQUE,
    description      VARCHAR(2000),
    contact_email    VARCHAR(254)  NOT NULL,
    contact_phone    VARCHAR(30),
    country          VARCHAR(2)    NOT NULL,
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    commission_rate  NUMERIC(5, 4) NOT NULL DEFAULT 0.1000 CHECK (commission_rate >= 0 AND commission_rate <= 0.5),
    status_reason    VARCHAR(500),
    submitted_at     TIMESTAMPTZ   NOT NULL,
    decided_at       TIMESTAMPTZ,
    decided_by       UUID,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_vendor_status ON vendor (status, submitted_at);

CREATE TABLE vendor_status_history (
    id          BIGSERIAL PRIMARY KEY,
    vendor_id   UUID        NOT NULL REFERENCES vendor (id) ON DELETE CASCADE,
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    actor_id    UUID,
    reason      VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_vendor_status_history_vendor ON vendor_status_history (vendor_id, occurred_at);

-- Append-only earnings ledger built from order events. (suborder_id, entry_type) makes each business fact
-- recordable exactly once even if the event is delivered repeatedly.
CREATE TABLE earnings_entry (
    id               UUID PRIMARY KEY,
    vendor_id        UUID           NOT NULL REFERENCES vendor (id),
    order_id         UUID           NOT NULL,
    suborder_id      UUID           NOT NULL,
    entry_type       VARCHAR(20)    NOT NULL CHECK (entry_type IN ('SALE', 'REFUND')),
    gross_amount     NUMERIC(12, 2) NOT NULL,
    commission       NUMERIC(12, 2) NOT NULL,
    net_amount       NUMERIC(12, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    occurred_at      TIMESTAMPTZ    NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ux_earnings_suborder_type UNIQUE (suborder_id, entry_type)
);
CREATE INDEX ix_earnings_vendor_time ON earnings_entry (vendor_id, occurred_at DESC);

CREATE TABLE payout (
    id         UUID PRIMARY KEY,
    vendor_id  UUID           NOT NULL REFERENCES vendor (id),
    amount     NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency   VARCHAR(3)     NOT NULL,
    reference  VARCHAR(100)   NOT NULL,
    status     VARCHAR(20)    NOT NULL DEFAULT 'PAID' CHECK (status IN ('PAID')),
    created_by UUID           NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ux_payout_reference UNIQUE (vendor_id, reference)
);
CREATE INDEX ix_payout_vendor ON payout (vendor_id, created_at DESC);

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
