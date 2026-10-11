-- notification-service owns contact projections, notifications and delivery attempts.

CREATE TABLE user_contact (
    user_id    UUID PRIMARY KEY,
    email      VARCHAR(254) NOT NULL,
    full_name  VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE vendor_contact (
    vendor_id      UUID PRIMARY KEY,
    owner_user_id  UUID         NOT NULL,
    email          VARCHAR(254) NOT NULL,
    business_name  VARCHAR(120) NOT NULL,
    vendor_version BIGINT       NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE notification (
    id                UUID PRIMARY KEY,
    recipient_user_id UUID,
    recipient_email   VARCHAR(254),
    channel           VARCHAR(10)  NOT NULL CHECK (channel IN ('EMAIL', 'IN_APP')),
    template          VARCHAR(60)  NOT NULL,
    subject           VARCHAR(200) NOT NULL,
    body              VARCHAR(4000) NOT NULL,
    link              VARCHAR(300),
    status            VARCHAR(12)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    -- business dedupe key: the same event never produces the same notification twice
    dedupe_key        VARCHAR(200) NOT NULL UNIQUE,
    source_event_id   UUID         NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(300),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    read_at           TIMESTAMPTZ
);
CREATE INDEX ix_notification_inbox ON notification (recipient_user_id, created_at DESC) WHERE channel = 'IN_APP';
CREATE INDEX ix_notification_pending ON notification (source_event_id) WHERE status = 'PENDING';
CREATE INDEX ix_notification_failed ON notification (created_at DESC) WHERE status = 'FAILED';

CREATE TABLE delivery_attempt (
    id              BIGSERIAL PRIMARY KEY,
    notification_id UUID        NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    outcome         VARCHAR(20) NOT NULL,
    error           VARCHAR(300),
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_delivery_attempt ON delivery_attempt (notification_id);

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
