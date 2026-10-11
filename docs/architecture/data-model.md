# Data model

Generated from the Flyway migrations by `docs/architecture/generate_er.py`. Each service owns its
database (ADR 0006); there are no foreign keys between services, only ids carried in events.

Every service also has the same two infrastructure tables:

- `outbox_event` (id, event_id, aggregate_type, aggregate_id, event_type, topic, msg_key, payload jsonb,
  status PENDING|PUBLISHED|FAILED, attempts, next_attempt_at, last_error, created_at, published_at):
  events written in the business transaction and published by `OutboxPublisher` (ADR 0003).
- `processed_event` (consumer, event_id, processed_at; primary key (consumer, event_id)): consumer
  deduplication written in the same transaction as the consumer's update (ADR 0005).

## analytics-service

```mermaid
erDiagram
    vendor_dim {
        uuid vendor_id PK
        uuid owner_user_id
        varchar_120 business_name
        varchar_20 status
        bigint vendor_version
    }
    order_fact {
        uuid order_id PK
        uuid customer_id
        varchar_30 status
        numeric_12_2 total
        varchar_3 currency
        int vendor_count
        int item_count
        varchar_100 cancel_reason
        timestamptz created_at
        timestamptz confirmed_at
        timestamptz cancelled_at
    }
    suborder_fact {
        uuid suborder_id PK
        uuid order_id
        uuid vendor_id
        varchar_120 vendor_name
        varchar_20 status
        numeric_12_2 total
        numeric_12_2 commission
        numeric_12_2 vendor_net
        timestamptz confirmed_at
    }
    item_fact {
        uuid suborder_id PK
        uuid variant_id PK
        uuid product_id
        uuid vendor_id
        varchar_200 product_name
        varchar_64 sku
        int quantity
        numeric_12_2 revenue
        timestamptz confirmed_at
        boolean cancelled
    }
    payment_fact {
        uuid order_id PK
        varchar_24 status
        numeric_12_2 amount
        numeric_12_2 refunded_amount
        varchar_60 failure_code
        timestamptz updated_at
    }
    inventory_alert {
        uuid variant_id PK
        uuid vendor_id
        uuid product_id
        varchar_64 sku
        varchar_200 product_name
        int available
        int threshold
        timestamptz detected_at
        timestamptz resolved_at
    }
    audit_log {
        uuid event_id PK
        varchar_60 service
        uuid actor_id
        varchar_20 actor_role
        varchar_80 action
        varchar_60 resource_type
        varchar_64 resource_id
        varchar_20 outcome
        jsonb details
        varchar_64 correlation_id
        timestamptz occurred_at
    }
    dead_letter {
        bigserial id PK
        varchar_200 topic
        int partition_no
        bigint offset_no
        varchar_100 consumer
        varchar_300 exception
        varchar_100 event_type
        varchar_64 event_id
        timestamptz received_at
    }
    consumer_checkpoint {
        varchar_60 stream PK
        timestamptz last_event_at
        bigint events_total
    }
```

## catalog-service

```mermaid
erDiagram
    category {
        uuid id PK
        uuid parent_id FK
        varchar_80 name
        varchar_100 slug UK
        varchar_500 description
        int sort_order
        timestamptz created_at
    }
    vendor_projection {
        uuid vendor_id PK
        uuid owner_user_id UK
        varchar_120 business_name
        varchar_140 slug
        varchar_20 status
        bigint vendor_version
        timestamptz updated_at
    }
    product {
        uuid id PK
        uuid vendor_id FK
        uuid category_id FK
        varchar_200 name
        varchar_220 slug
        varchar_5000 description
        varchar_100 brand
        jsonb attributes
        varchar_3 currency
        varchar_20 status
        varchar_500 moderation_note
        numeric_12_2 min_price
        numeric_12_2 max_price
        boolean in_stock
        numeric_3_2 rating_avg
        int rating_count
        int max_per_order
        timestamptz published_at
        timestamptz created_at
        timestamptz updated_at
        bigint version
        tsvector search_vector
    }
    product_variant {
        uuid id PK
        uuid product_id FK
        varchar_64 sku UK
        varchar_120 name
        jsonb attributes
        numeric_12_2 price
        numeric_12_2 compare_at_price
        boolean active
        int available_qty
        bigint stock_version
        int sort_order
        timestamptz created_at
    }
    product_image {
        uuid id PK
        uuid product_id FK
        varchar_500 url
        varchar_200 alt_text
        int sort_order
    }
    category ||--o{ category : "parent_id"
    vendor_projection ||--o{ product : "vendor_id"
    category ||--o{ product : "category_id"
    product ||--o{ product_variant : "product_id"
    product ||--o{ product_image : "product_id"
```

## identity-service

```mermaid
erDiagram
    app_user {
        uuid id PK
        varchar_254 email
        varchar_100 password_hash
        varchar_120 full_name
        varchar_20 status
        int failed_login_count
        timestamptz locked_until
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    user_role {
        uuid user_id PK,FK
        varchar_20 role PK
        timestamptz granted_at
    }
    refresh_token {
        uuid id PK
        uuid user_id FK
        varchar_64 token_hash UK
        uuid family_id
        timestamptz expires_at
        timestamptz revoked_at
        uuid replaced_by
        timestamptz created_at
    }
    password_reset_token {
        uuid id PK
        uuid user_id FK
        varchar_64 token_hash UK
        timestamptz expires_at
        timestamptz used_at
        timestamptz created_at
    }
    user_address {
        uuid id PK
        uuid user_id FK
        varchar_40 label
        varchar_120 recipient
        varchar_200 line1
        varchar_200 line2
        varchar_100 city
        varchar_100 state
        varchar_20 postal_code
        varchar_2 country
        varchar_30 phone
        boolean is_default
        timestamptz created_at
    }
    app_user ||--o{ user_role : "user_id"
    app_user ||--o{ refresh_token : "user_id"
    app_user ||--o{ password_reset_token : "user_id"
    app_user ||--o{ user_address : "user_id"
```

## inventory-service

```mermaid
erDiagram
    vendor_projection {
        uuid vendor_id PK
        uuid owner_user_id UK
        varchar_20 status
        bigint vendor_version
        timestamptz updated_at
    }
    inventory_item {
        uuid id PK
        uuid variant_id UK
        uuid product_id
        uuid vendor_id
        varchar_64 sku
        varchar_200 product_name
        varchar_120 variant_name
        int available
        int reserved
        int sold
        int low_stock_threshold
        bigint product_version
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }
    reservation {
        uuid id PK
        uuid order_id UK
        varchar_20 status
        varchar_300 failure_reason
        timestamptz expires_at
        timestamptz created_at
        timestamptz updated_at
    }
    reservation_line {
        uuid reservation_id PK,FK
        uuid inventory_item_id FK
        uuid variant_id PK
        int quantity
    }
    stock_movement {
        bigserial id PK
        uuid inventory_item_id FK
        varchar_20 movement_type
        int delta_available
        int delta_reserved
        int delta_sold
        varchar_30 reference_type
        varchar_64 reference_id
        uuid actor_id
        varchar_300 reason
        timestamptz created_at
    }
    reservation ||--o{ reservation_line : "reservation_id"
    inventory_item ||--o{ reservation_line : "inventory_item_id"
    inventory_item ||--o{ stock_movement : "inventory_item_id"
```

## notification-service

```mermaid
erDiagram
    user_contact {
        uuid user_id PK
        varchar_254 email
        varchar_120 full_name
        timestamptz updated_at
    }
    vendor_contact {
        uuid vendor_id PK
        uuid owner_user_id
        varchar_254 email
        varchar_120 business_name
        bigint vendor_version
        timestamptz updated_at
    }
    notification {
        uuid id PK
        uuid recipient_user_id
        varchar_254 recipient_email
        varchar_10 channel
        varchar_60 template
        varchar_200 subject
        varchar_4000 body
        varchar_300 link
        varchar_12 status
        varchar_200 dedupe_key UK
        uuid source_event_id
        int attempts
        varchar_300 last_error
        timestamptz created_at
        timestamptz sent_at
        timestamptz read_at
    }
    delivery_attempt {
        bigserial id PK
        uuid notification_id FK
        varchar_20 outcome
        varchar_300 error
        timestamptz attempted_at
    }
    notification ||--o{ delivery_attempt : "notification_id"
```

## order-service

```mermaid
erDiagram
    vendor_projection {
        uuid vendor_id PK
        uuid owner_user_id UK
        varchar_120 business_name
        varchar_20 status
        numeric_5_4 commission_rate
        bigint vendor_version
        timestamptz updated_at
    }
    product_projection {
        uuid product_id PK
        uuid vendor_id
        varchar_200 name
        varchar_20 status
        varchar_3 currency
        int max_per_order
        varchar_500 image_url
        bigint product_version
        timestamptz updated_at
    }
    variant_projection {
        uuid variant_id PK
        uuid product_id FK
        varchar_64 sku
        varchar_120 name
        numeric_12_2 price
        boolean active
    }
    customer_order {
        uuid id PK
        uuid customer_id
        varchar_254 customer_email
        varchar_30 status
        varchar_3 currency
        numeric_12_2 items_subtotal
        numeric_12_2 discount_total
        numeric_12_2 shipping_total
        numeric_12_2 tax_total
        numeric_12_2 grand_total
        varchar_40 coupon_code
        jsonb shipping_address
        varchar_100 idempotency_key
        varchar_64 request_hash
        varchar_300 cancel_reason
        timestamptz created_at
        timestamptz updated_at
        timestamptz confirmed_at
        timestamptz cancelled_at
        bigint version
    }
    suborder {
        uuid id PK
        uuid order_id FK
        uuid vendor_id
        varchar_120 vendor_name
        varchar_20 status
        numeric_12_2 items_subtotal
        numeric_12_2 discount_amount
        numeric_12_2 shipping_amount
        numeric_12_2 tax_amount
        numeric_12_2 total
        numeric_5_4 commission_rate
        numeric_12_2 commission_amount
        numeric_12_2 vendor_net_amount
        varchar_60 carrier
        varchar_100 tracking_number
        timestamptz shipped_at
        timestamptz delivered_at
        bigint version
    }
    order_item {
        uuid id PK
        uuid order_id FK
        uuid suborder_id FK
        uuid product_id
        uuid variant_id
        varchar_64 sku
        varchar_200 product_name
        varchar_120 variant_name
        varchar_500 image_url
        int quantity
        numeric_12_2 unit_price
        numeric_12_2 line_subtotal
        numeric_12_2 discount_amount
        numeric_12_2 line_total
    }
    order_status_history {
        bigserial id PK
        uuid order_id FK
        uuid suborder_id
        varchar_30 from_status
        varchar_30 to_status
        varchar_300 reason
        uuid actor_id
        timestamptz occurred_at
    }
    checkout_saga {
        uuid order_id PK,FK
        varchar_40 state
        int attempt
        timestamptz deadline_at
        varchar_100 payment_token
        varchar_100 pending_compensations
        varchar_300 compensation_reason
        varchar_500 last_error
        timestamptz created_at
        timestamptz updated_at
        timestamptz completed_at
        bigint version
    }
    saga_transition {
        bigserial id PK
        uuid order_id FK
        varchar_40 from_state
        varchar_40 to_state
        varchar_60 trigger
        uuid event_id
        varchar_300 detail
        timestamptz occurred_at
    }
    cancellation {
        uuid order_id PK,FK
        uuid requested_by
        varchar_300 reason
        boolean refund_done
        boolean restock_done
        varchar_300 failed_reason
        timestamptz created_at
        timestamptz completed_at
    }
    product_projection ||--o{ variant_projection : "product_id"
    customer_order ||--o{ suborder : "order_id"
    customer_order ||--o{ order_item : "order_id"
    suborder ||--o{ order_item : "suborder_id"
    customer_order ||--o{ order_status_history : "order_id"
    customer_order ||--o{ checkout_saga : "order_id"
    checkout_saga ||--o{ saga_transition : "order_id"
    customer_order ||--o{ cancellation : "order_id"
```

## payment-service

```mermaid
erDiagram
    payment {
        uuid id PK
        uuid order_id UK
        uuid customer_id
        numeric_12_2 amount
        varchar_3 currency
        varchar_24 status
        varchar_30 provider
        varchar_100 provider_payment_id UK
        varchar_100 payment_token
        varchar_20 in_flight
        timestamptz in_flight_since
        varchar_20 last_operation
        varchar_60 failure_code
        numeric_12_2 refunded_amount
        int recon_attempts
        timestamptz authorized_at
        timestamptz captured_at
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    payment_attempt {
        bigserial id PK
        uuid payment_id FK
        varchar_20 operation
        varchar_30 outcome
        varchar_100 provider_reference
        varchar_60 error_code
        bigint duration_ms
        timestamptz created_at
    }
    refund {
        uuid id PK
        uuid payment_id FK
        numeric_12_2 amount
        varchar_20 status
        varchar_300 reason
        varchar_120 refund_key UK
        varchar_100 provider_refund_id
        uuid requested_by
        timestamptz created_at
        timestamptz completed_at
    }
    webhook_event {
        varchar_100 provider_event_id PK
        varchar_60 event_type
        varchar_64 payload_sha256
        timestamptz received_at
    }
    sandbox_provider_payment {
        varchar_100 provider_payment_id PK
        varchar_100 merchant_reference UK
        varchar_100 token
        numeric_12_2 amount
        varchar_24 status
        numeric_12_2 refunded
        timestamptz created_at
        timestamptz updated_at
    }
    sandbox_idempotency {
        varchar_150 idempotency_key PK
        text response
        timestamptz created_at
    }
    sandbox_pending_webhook {
        bigserial id PK
        varchar_100 event_id UK
        varchar_60 event_type
        varchar_100 provider_payment_id
        text body
        timestamptz deliver_at
        int attempts
        boolean delivered
    }
    payment ||--o{ payment_attempt : "payment_id"
    payment ||--o{ refund : "payment_id"
```

## review-service

```mermaid
erDiagram
    purchase {
        uuid customer_id PK
        uuid product_id PK
        uuid order_id PK
        timestamptz purchased_at
        boolean cancelled
    }
    review {
        uuid id PK
        uuid product_id
        uuid customer_id
        varchar_120 author_name
        smallint rating
        varchar_120 title
        varchar_4000 body
        varchar_12 status
        boolean verified_purchase
        varchar_300 moderation_note
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    product_rating {
        uuid product_id PK
        int review_count
        numeric_3_2 average
        jsonb histogram
        timestamptz updated_at
    }
```

## vendor-service

```mermaid
erDiagram
    vendor {
        uuid id PK
        uuid owner_user_id UK
        varchar_120 business_name
        varchar_140 slug UK
        varchar_2000 description
        varchar_254 contact_email
        varchar_30 contact_phone
        varchar_2 country
        varchar_20 status
        numeric_5_4 commission_rate
        varchar_500 status_reason
        timestamptz submitted_at
        timestamptz decided_at
        uuid decided_by
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    vendor_status_history {
        bigserial id PK
        uuid vendor_id FK
        varchar_20 from_status
        varchar_20 to_status
        uuid actor_id
        varchar_500 reason
        timestamptz occurred_at
    }
    earnings_entry {
        uuid id PK
        uuid vendor_id FK
        uuid order_id
        uuid suborder_id
        varchar_20 entry_type
        numeric_12_2 gross_amount
        numeric_12_2 commission
        numeric_12_2 net_amount
        varchar_3 currency
        timestamptz occurred_at
        timestamptz created_at
    }
    payout {
        uuid id PK
        uuid vendor_id FK
        numeric_12_2 amount
        varchar_3 currency
        varchar_100 reference
        varchar_20 status
        uuid created_by
        timestamptz created_at
    }
    vendor ||--o{ vendor_status_history : "vendor_id"
    vendor ||--o{ earnings_entry : "vendor_id"
    vendor ||--o{ payout : "vendor_id"
```
