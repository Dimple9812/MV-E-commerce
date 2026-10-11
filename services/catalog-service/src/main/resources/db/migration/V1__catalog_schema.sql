-- catalog-service owns categories, products, variants (SKUs), images and the storefront read model.

CREATE TABLE category (
    id          UUID PRIMARY KEY,
    parent_id   UUID REFERENCES category (id),
    name        VARCHAR(80)  NOT NULL,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_category_parent ON category (parent_id);

-- Projection of vendor-service data, maintained from vendor.events. Used for ownership checks and to hide
-- products of vendors that are not APPROVED. vendor_version discards out-of-order events.
CREATE TABLE vendor_projection (
    vendor_id      UUID PRIMARY KEY,
    owner_user_id  UUID         NOT NULL UNIQUE,
    business_name  VARCHAR(120) NOT NULL,
    slug           VARCHAR(140) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    vendor_version BIGINT       NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE product (
    id             UUID PRIMARY KEY,
    vendor_id      UUID           NOT NULL REFERENCES vendor_projection (vendor_id),
    category_id    UUID           NOT NULL REFERENCES category (id),
    name           VARCHAR(200)   NOT NULL,
    slug           VARCHAR(220)   NOT NULL,
    description    VARCHAR(5000)  NOT NULL DEFAULT '',
    brand          VARCHAR(100),
    attributes     JSONB          NOT NULL DEFAULT '{}'::jsonb,
    currency       VARCHAR(3)     NOT NULL DEFAULT 'USD',
    status         VARCHAR(20)    NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED', 'BLOCKED')),
    moderation_note VARCHAR(500),
    min_price      NUMERIC(12, 2),
    max_price      NUMERIC(12, 2),
    in_stock       BOOLEAN        NOT NULL DEFAULT false,
    rating_avg     NUMERIC(3, 2)  NOT NULL DEFAULT 0 CHECK (rating_avg >= 0 AND rating_avg <= 5),
    rating_count   INT            NOT NULL DEFAULT 0 CHECK (rating_count >= 0),
    max_per_order  INT            NOT NULL DEFAULT 10 CHECK (max_per_order BETWEEN 1 AND 100),
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version        BIGINT         NOT NULL DEFAULT 0,
    search_vector  TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(brand, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'C')) STORED,
    CONSTRAINT ux_product_vendor_slug UNIQUE (vendor_id, slug)
);
CREATE INDEX ix_product_search ON product USING GIN (search_vector);
CREATE INDEX ix_product_storefront ON product (status, category_id, published_at DESC);
CREATE INDEX ix_product_vendor ON product (vendor_id, status);
CREATE INDEX ix_product_price ON product (min_price) WHERE status = 'PUBLISHED';

CREATE TABLE product_variant (
    id               UUID PRIMARY KEY,
    product_id       UUID           NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sku              VARCHAR(64)    NOT NULL UNIQUE,
    name             VARCHAR(120)   NOT NULL,
    attributes       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    price            NUMERIC(12, 2) NOT NULL CHECK (price > 0 AND price < 1000000),
    compare_at_price NUMERIC(12, 2) CHECK (compare_at_price IS NULL OR compare_at_price > 0),
    active           BOOLEAN        NOT NULL DEFAULT true,
    available_qty    INT            NOT NULL DEFAULT 0,  -- projection from inventory.events (display only)
    stock_version    BIGINT         NOT NULL DEFAULT 0,  -- inventory item version; stale events are ignored
    sort_order       INT            NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_variant_product ON product_variant (product_id);

CREATE TABLE product_image (
    id         UUID PRIMARY KEY,
    product_id UUID         NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    url        VARCHAR(500) NOT NULL,
    alt_text   VARCHAR(200),
    sort_order INT          NOT NULL DEFAULT 0
);
CREATE INDEX ix_image_product ON product_image (product_id, sort_order);

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

-- Reference data: the category tree is part of the platform, not demo data.
INSERT INTO category (id, parent_id, name, slug, description, sort_order) VALUES
 ('10000000-0000-0000-0000-000000000001', NULL, 'Electronics', 'electronics', 'Gadgets, audio and accessories', 1),
 ('10000000-0000-0000-0000-000000000002', NULL, 'Home & Kitchen', 'home-kitchen', 'Cookware, decor and storage', 2),
 ('10000000-0000-0000-0000-000000000003', NULL, 'Fashion', 'fashion', 'Clothing, shoes and bags', 3),
 ('10000000-0000-0000-0000-000000000004', NULL, 'Sports & Outdoors', 'sports-outdoors', 'Gear for active life', 4),
 ('10000000-0000-0000-0000-000000000005', NULL, 'Books & Stationery', 'books-stationery', 'Reading and writing', 5),
 ('10000000-0000-0000-0000-000000000006', NULL, 'Beauty & Care', 'beauty-care', 'Skincare and grooming', 6),
 ('10000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001', 'Audio', 'audio', 'Headphones and speakers', 1),
 ('10000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000001', 'Accessories', 'electronics-accessories', 'Chargers, cables, cases', 2),
 ('10000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000002', 'Cookware', 'cookware', 'Pans, pots and knives', 1),
 ('10000000-0000-0000-0000-000000000022', '10000000-0000-0000-0000-000000000002', 'Decor', 'decor', 'Lighting and ornaments', 2),
 ('10000000-0000-0000-0000-000000000031', '10000000-0000-0000-0000-000000000003', 'Apparel', 'apparel', 'Tops, bottoms and outerwear', 1),
 ('10000000-0000-0000-0000-000000000032', '10000000-0000-0000-0000-000000000003', 'Footwear', 'footwear', 'Sneakers and boots', 2);
