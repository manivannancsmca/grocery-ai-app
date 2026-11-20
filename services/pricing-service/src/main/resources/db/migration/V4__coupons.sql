-- Phase 12 §2: promotions & coupons. IDs: UUIDv7 as BINARY(16); timestamps DATETIME(6) in UTC; money DECIMAL(12,2) INR.
-- Concurrency rule (see CouponRedemptionService.java): a redemption is only ever created while its coupon's row is
-- locked (SELECT ... FOR UPDATE, the first statement of the transaction), so the usage limits, which count RESERVED and
-- CONFIRMED redemptions, hold under concurrent orders.

-- A coupon. PLATFORM coupons are created by admins and funded by the platform (any store, optionally only GROCERY or
-- RESTAURANT stores); STORE coupons belong to one vendor (GROCERY) or restaurant (RESTAURANT) and are funded by it.
CREATE TABLE coupon (
    id                  BINARY(16)     NOT NULL,
    code                VARCHAR(20)    NOT NULL,   -- ^[A-Z0-9-]{4,20}$, stored upper-case
    name                VARCHAR(80)    NOT NULL,
    description         VARCHAR(300)   NULL,
    scope               VARCHAR(10)    NOT NULL,   -- PLATFORM | STORE
    store_type          VARCHAR(12)    NOT NULL,   -- GROCERY | RESTAURANT | ANY (ANY only for PLATFORM)
    store_id            BINARY(16)     NULL,       -- STORE only: the vendor id or restaurant id
    discount_type       VARCHAR(15)    NOT NULL,   -- PERCENT | FLAT | FREE_DELIVERY
    discount_value      DECIMAL(12, 2) NULL,       -- PERCENT: 1-90 (%); FLAT: the amount; NULL for FREE_DELIVERY
    max_discount        DECIMAL(12, 2) NULL,       -- PERCENT only (optional cap)
    min_order_subtotal  DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    valid_from          DATETIME(6)    NOT NULL,
    valid_until         DATETIME(6)    NOT NULL,   -- exclusive
    total_usage_limit   INT            NULL,       -- NULL = unlimited
    per_customer_limit  INT            NOT NULL DEFAULT 1,
    active              BOOLEAN        NOT NULL,   -- false = paused
    public_offer        BOOLEAN        NOT NULL,   -- listed to customers as an offer (GET /api/coupons)
    created_by          BINARY(16)     NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    updated_by          BINARY(16)     NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_coupon PRIMARY KEY (id),
    CONSTRAINT uk_coupon_code UNIQUE (code),
    CONSTRAINT ck_coupon_scope CHECK ((scope = 'PLATFORM' AND store_id IS NULL)
        OR (scope = 'STORE' AND store_id IS NOT NULL AND store_type IN ('GROCERY', 'RESTAURANT'))),
    CONSTRAINT ck_coupon_validity CHECK (valid_until > valid_from),
    CONSTRAINT ck_coupon_limits CHECK (per_customer_limit >= 1 AND (total_usage_limit IS NULL OR total_usage_limit >= 1)),
    INDEX idx_coupon_store_created (store_id, created_at),
    INDEX idx_coupon_scope_created (scope, created_at),
    INDEX idx_coupon_offers (active, public_offer, valid_until)
) ENGINE = InnoDB;

-- One coupon use per order (order_id unique: redeem is idempotent per order). RESERVED at placement (redeem),
-- CONFIRMED on OrderCreated, RELEASED on OrderCancelled / OrderRejected or by the sweeper when no OrderCreated came
-- within COUPON_RESERVATION_TTL (the order was never saved). Usage limits count RESERVED and CONFIRMED.
CREATE TABLE coupon_redemption (
    id                     BINARY(16)     NOT NULL,
    order_id               BINARY(16)     NOT NULL,
    order_number           VARCHAR(20)    NULL,       -- GA-XXXXXXXX, learnt from the order events (unknown at redeem)
    coupon_id              BINARY(16)     NOT NULL,
    coupon_code            VARCHAR(20)    NOT NULL,   -- the code at redemption time
    customer_id            BINARY(16)     NOT NULL,
    store_id               BINARY(16)     NOT NULL,
    order_type             VARCHAR(12)    NOT NULL,   -- GROCERY | RESTAURANT
    subtotal               DECIMAL(12, 2) NOT NULL,
    delivery_fee           DECIMAL(12, 2) NOT NULL,
    discount               DECIMAL(12, 2) NOT NULL,   -- on the item subtotal
    delivery_fee_discount  DECIMAL(12, 2) NOT NULL,   -- FREE_DELIVERY
    status                 VARCHAR(10)    NOT NULL,   -- RESERVED | CONFIRMED | RELEASED
    reserved_at            DATETIME(6)    NOT NULL,
    confirmed_at           DATETIME(6)    NULL,
    released_at            DATETIME(6)    NULL,
    release_reason         VARCHAR(30)    NULL,       -- ORDER_CANCELLED | ORDER_REJECTED | RESERVATION_EXPIRED
    CONSTRAINT pk_coupon_redemption PRIMARY KEY (id),
    CONSTRAINT uk_coupon_redemption_order UNIQUE (order_id),
    CONSTRAINT fk_coupon_redemption_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (id),
    INDEX idx_coupon_redemption_coupon_status (coupon_id, status),
    INDEX idx_coupon_redemption_coupon_customer (coupon_id, customer_id, status),
    INDEX idx_coupon_redemption_status_reserved (status, reserved_at),
    INDEX idx_coupon_redemption_coupon_reserved (coupon_id, reserved_at)
) ENGINE = InnoDB;
