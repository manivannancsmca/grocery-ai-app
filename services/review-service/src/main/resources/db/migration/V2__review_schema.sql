-- Phase 12 §1 (docs/api/phase-12-post-mvp-features.md). IDs: UUIDs as BINARY(16); timestamps DATETIME(6) in UTC.
-- This service never reads another service's database: what can be reviewed comes from order.events.

-- Eligibility projection, one row per order, filled by OrderConfirmed (items, store, type) and OrderDelivered
-- (partner, time) in any order (group review-service.order-events, idempotent with processed_event).
CREATE TABLE review_order (
    order_id      BINARY(16)   NOT NULL,
    order_number  VARCHAR(40)  NOT NULL,
    order_type    VARCHAR(20)  NOT NULL,              -- GROCERY | RESTAURANT
    customer_id   BINARY(16)   NOT NULL,
    store_id      BINARY(16)   NOT NULL,              -- vendorId (GROCERY) or restaurantId (RESTAURANT)
    store_name    VARCHAR(200) NULL,                  -- OrderConfirmed.storeLocation.name, when known
    confirmed_at  DATETIME(6)  NULL,                  -- set by OrderConfirmed (its items are then known)
    partner_id    BINARY(16)   NULL,                  -- set by OrderDelivered
    delivered_at  DATETIME(6)  NULL,                  -- set by OrderDelivered: the 30-day review window starts here
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_review_order PRIMARY KEY (order_id),
    INDEX idx_review_order_customer_delivered (customer_id, delivered_at)
) ENGINE = InnoDB;

-- The products or dishes of an order (OrderConfirmed lines; one row per distinct item).
CREATE TABLE review_order_item (
    order_id   BINARY(16)   NOT NULL,
    item_type  VARCHAR(20)  NOT NULL,                 -- PRODUCT | MENU_ITEM
    item_id    BINARY(16)   NOT NULL,
    name       VARCHAR(200) NOT NULL,                 -- the name at ordering time
    line_no    INT          NOT NULL,                 -- position in the order (display order)
    CONSTRAINT pk_review_order_item PRIMARY KEY (order_id, item_type, item_id)
) ENGINE = InnoDB;

-- One review per customer, order and target. Hard-deleted when the customer deletes it.
CREATE TABLE review (
    id                 BINARY(16)    NOT NULL,
    customer_id        BINARY(16)    NOT NULL,
    order_id           BINARY(16)    NOT NULL,
    order_number       VARCHAR(40)   NOT NULL,
    target_type        VARCHAR(20)   NOT NULL,        -- PRODUCT | MENU_ITEM | VENDOR | RESTAURANT | DELIVERY_PARTNER
    target_id          BINARY(16)    NOT NULL,
    target_name        VARCHAR(200)  NOT NULL,
    store_id           BINARY(16)    NOT NULL,        -- the order's store (whose owner may reply to its reviews)
    store_name         VARCHAR(200)  NULL,            -- the store's name from the order, when known
    rating             TINYINT       NOT NULL,        -- 1..5
    title              VARCHAR(80)   NULL,
    comment            VARCHAR(1000) NULL,
    author_name        VARCHAR(100)  NOT NULL,        -- "Asha S.": first name and last initial from the JWT name
    status             VARCHAR(20)   NOT NULL,        -- PUBLISHED | FLAGGED | REJECTED | REMOVED
    flag_reason        VARCHAR(40)   NULL,            -- EMAIL | LINK | PHONE | PROFANITY | UPPER_CASE (when FLAGGED)
    moderation_reason  VARCHAR(300)  NULL,            -- the admin's reason (REJECTED, REMOVED)
    moderated_by       BINARY(16)    NULL,
    moderated_at       DATETIME(6)   NULL,
    reply_text         VARCHAR(500)  NULL,            -- the store's public reply
    reply_by           BINARY(16)    NULL,
    replied_at         DATETIME(6)   NULL,            -- first reply
    reply_updated_at   DATETIME(6)   NULL,            -- last reply edit
    edited             BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         DATETIME(6)   NOT NULL,        -- the 7-day edit window starts here
    submitted_at       DATETIME(6)   NOT NULL,        -- the last submission (creation or edit)
    updated_at         DATETIME(6)   NOT NULL,
    version            BIGINT        NOT NULL,
    CONSTRAINT pk_review PRIMARY KEY (id),
    CONSTRAINT uk_review_customer_order_target UNIQUE (customer_id, order_id, target_type, target_id),
    INDEX idx_review_target_status_created (target_type, target_id, status, created_at),
    INDEX idx_review_target_status_rating (target_type, target_id, status, rating),
    INDEX idx_review_customer_created (customer_id, created_at),
    INDEX idx_review_store_status_created (store_id, status, created_at),
    INDEX idx_review_status_submitted (status, submitted_at)
) ENGINE = InnoDB;

-- The published-rating summary of every reviewed target; summary_version increases with every change and is sent in
-- RatingSummaryUpdated. Changes lock the row first (INSERT … ON DUPLICATE KEY UPDATE), so concurrent changes of one
-- target are serialised and each recomputation sees the previous ones.
CREATE TABLE rating_summary (
    target_type      VARCHAR(20)  NOT NULL,
    target_id        BINARY(16)   NOT NULL,
    average_rating   DECIMAL(3,2) NOT NULL,           -- 0.00 when rating_count = 0
    rating_count     INT          NOT NULL,
    rating_1         INT          NOT NULL,
    rating_2         INT          NOT NULL,
    rating_3         INT          NOT NULL,
    rating_4         INT          NOT NULL,
    rating_5         INT          NOT NULL,
    summary_version  BIGINT       NOT NULL,           -- 0 = never published
    updated_at       DATETIME(6)  NOT NULL,
    CONSTRAINT pk_rating_summary PRIMARY KEY (target_type, target_id)
) ENGINE = InnoDB;
