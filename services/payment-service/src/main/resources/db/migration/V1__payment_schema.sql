-- payment-service owns payments, attempts, refunds and webhook receipts.
-- No card numbers or CVVs exist anywhere: clients only ever send provider tokens (tok_...).

CREATE TABLE payment (
    id                  UUID PRIMARY KEY,
    order_id            UUID           NOT NULL UNIQUE,      -- one payment per order: idempotent authorisation
    customer_id         UUID           NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(24)    NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'DECLINED', 'UNKNOWN',
                                          'CAPTURED', 'CAPTURE_FAILED', 'VOIDED', 'REFUNDED', 'PARTIALLY_REFUNDED')),
    provider            VARCHAR(30)    NOT NULL,
    provider_payment_id VARCHAR(100) UNIQUE,
    payment_token       VARCHAR(100)   NOT NULL,
    in_flight           VARCHAR(20),                         -- operation lease (AUTHORIZE/CAPTURE/VOID/REFUND)
    in_flight_since     TIMESTAMPTZ,
    last_operation      VARCHAR(20),
    failure_code        VARCHAR(60),
    refunded_amount     NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    recon_attempts      INT            NOT NULL DEFAULT 0,
    authorized_at       TIMESTAMPTZ,
    captured_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_refund_not_above_amount CHECK (refunded_amount <= amount)
);
CREATE INDEX ix_payment_customer ON payment (customer_id, created_at DESC);
CREATE INDEX ix_payment_status ON payment (status, updated_at);
CREATE INDEX ix_payment_reconcile ON payment (updated_at) WHERE status IN ('PENDING', 'UNKNOWN') OR in_flight IS NOT NULL;

CREATE TABLE payment_attempt (
    id                 BIGSERIAL PRIMARY KEY,
    payment_id         UUID        NOT NULL REFERENCES payment (id) ON DELETE CASCADE,
    operation          VARCHAR(20) NOT NULL,
    outcome            VARCHAR(30) NOT NULL,
    provider_reference VARCHAR(100),
    error_code         VARCHAR(60),
    duration_ms        BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_attempt_payment ON payment_attempt (payment_id, id);

CREATE TABLE refund (
    id                 UUID PRIMARY KEY,
    payment_id         UUID           NOT NULL REFERENCES payment (id),
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status             VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    reason             VARCHAR(300)   NOT NULL,
    refund_key         VARCHAR(120)   NOT NULL UNIQUE,      -- idempotency key of the refund request
    provider_refund_id VARCHAR(100),
    requested_by       UUID,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ
);
CREATE INDEX ix_refund_payment ON refund (payment_id);

-- Webhook replay protection: a provider event ID is processed at most once.
CREATE TABLE webhook_event (
    provider_event_id VARCHAR(100) PRIMARY KEY,
    event_type        VARCHAR(60)  NOT NULL,
    payload_sha256    VARCHAR(64)  NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---- Sandbox provider storage --------------------------------------------------------------------------------
-- These tables simulate the EXTERNAL payment provider's own database (it remembers what really happened even when
-- our request timed out). They are only used by SandboxPaymentProvider; a real provider adapter would not need them.
CREATE TABLE sandbox_provider_payment (
    provider_payment_id VARCHAR(100) PRIMARY KEY,
    merchant_reference  VARCHAR(100)   NOT NULL UNIQUE,
    token               VARCHAR(100)   NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL,
    status              VARCHAR(24)    NOT NULL,
    refunded            NUMERIC(12, 2) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE sandbox_idempotency (
    idempotency_key VARCHAR(150) PRIMARY KEY,
    response        TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sandbox_pending_webhook (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(100) NOT NULL UNIQUE,
    event_type  VARCHAR(60)  NOT NULL,
    provider_payment_id VARCHAR(100) NOT NULL,
    body        TEXT         NOT NULL,
    deliver_at  TIMESTAMPTZ  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    delivered   BOOLEAN      NOT NULL DEFAULT false
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
