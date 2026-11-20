-- Phase 12 (docs/api/phase-12-post-mvp-features.md §2, §3): the coupon redeemed at placement and returns.

-- The coupon snapshot (§2): redeemed at pricing-service before the order is saved, never changed afterwards.
-- discount = off the item subtotal, delivery_fee_discount = off the delivery fee (FREE_DELIVERY);
-- total = subtotal + delivery_fee - discount - delivery_fee_discount. Both 0.00 without a coupon.
ALTER TABLE customer_order
    ADD COLUMN coupon_code            VARCHAR(40)    NULL AFTER delivery_fee,
    ADD COLUMN coupon_redemption_id   BINARY(16)     NULL AFTER coupon_code,   -- pricing-service's redemption
    ADD COLUMN discount               DECIMAL(12,2)  NOT NULL DEFAULT 0.00 AFTER coupon_redemption_id,
    ADD COLUMN delivery_fee_discount  DECIMAL(12,2)  NOT NULL DEFAULT 0.00 AFTER discount,
    ADD CONSTRAINT ck_customer_order_discounts CHECK (discount >= 0 AND delivery_fee_discount >= 0
                                                      AND discount <= subtotal
                                                      AND delivery_fee_discount <= delivery_fee);

-- A return request (§3) of a DELIVERED order: REQUESTED -> APPROVED -> REFUNDED; REQUESTED -> REJECTED;
-- REQUESTED -> ESCALATED (the store did not decide by decide_by) -> APPROVED / REJECTED by an admin.
-- The order's number, type, customer and store are snapshotted, so lists and events need no join.
-- Locking: every change locks the customer_order row first, then this row (service/ReturnService.java); the
-- escalation poller only claims REQUESTED rows here with FOR UPDATE SKIP LOCKED.
CREATE TABLE order_return (
    id                    BINARY(16)     NOT NULL,
    return_number         VARCHAR(12)    NOT NULL,   -- "RT-" + 8 Crockford base32 characters
    order_id              BINARY(16)     NOT NULL,
    order_number          VARCHAR(12)    NOT NULL,
    order_type            VARCHAR(10)    NOT NULL,   -- GROCERY | RESTAURANT
    customer_id           BINARY(16)     NOT NULL,
    store_id              BINARY(16)     NOT NULL,   -- vendorId (GROCERY) or restaurantId (RESTAURANT)
    store_name            VARCHAR(150)   NULL,
    status                VARCHAR(10)    NOT NULL,   -- REQUESTED | ESCALATED | APPROVED | REJECTED | REFUNDED
    reason                VARCHAR(20)    NOT NULL,   -- DAMAGED | WRONG_ITEM | MISSING | QUALITY | OTHER
    note                  VARCHAR(500)   NULL,
    refund_amount         DECIMAL(12,2)  NOT NULL,   -- sum of the lines + delivery_fee_refund
    delivery_fee_refund   DECIMAL(12,2)  NOT NULL DEFAULT 0.00,   -- the paid delivery fee, on a full return only
    restock               BOOLEAN        NULL,       -- set on approval (always FALSE for RESTAURANT)
    decide_by             DATETIME(6)    NOT NULL,   -- requested_at + grocery.order.returns.decision-timeout
    requested_at          DATETIME(6)    NOT NULL,
    escalated_at          DATETIME(6)    NULL,
    decided_at            DATETIME(6)    NULL,
    decided_by            BINARY(16)     NULL,       -- the store user or admin who decided
    decided_by_role       VARCHAR(10)    NULL,       -- STORE | ADMIN
    comment               VARCHAR(300)   NULL,       -- why it was rejected
    refund_id             BINARY(16)     NULL,       -- payment-service's refund (RefundCompleted)
    refunded_at           DATETIME(6)    NULL,
    updated_at            DATETIME(6)    NOT NULL,
    version               BIGINT         NOT NULL DEFAULT 0,
    -- one open request (REQUESTED / ESCALATED) per order at a time: NULL for every other status
    open_order_id         BINARY(16)     AS (CASE WHEN status IN ('REQUESTED', 'ESCALATED') THEN order_id END) STORED,
    CONSTRAINT pk_order_return PRIMARY KEY (id),
    CONSTRAINT uk_order_return_number UNIQUE (return_number),
    CONSTRAINT uk_order_return_open UNIQUE (open_order_id),
    CONSTRAINT fk_order_return_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
    CONSTRAINT ck_order_return_amounts CHECK (refund_amount >= 0 AND delivery_fee_refund >= 0),
    INDEX idx_order_return_order (order_id, requested_at),
    INDEX idx_order_return_customer (customer_id, requested_at),
    INDEX idx_order_return_store (store_id, order_type, requested_at),
    INDEX idx_order_return_status_decide (status, decide_by),
    INDEX idx_order_return_status_requested (status, requested_at)
) ENGINE = InnoDB;

-- The returned lines (quantity and prorated refund each), in request order.
CREATE TABLE order_return_item (
    return_id      BINARY(16)     NOT NULL,
    line_no        INT            NOT NULL,
    item_id        BINARY(16)     NOT NULL,   -- productId / menuItemId of the order line
    name           VARCHAR(200)   NOT NULL,
    quantity       INT            NOT NULL,
    refund_amount  DECIMAL(12,2)  NOT NULL,
    CONSTRAINT pk_order_return_item PRIMARY KEY (return_id, line_no),
    CONSTRAINT fk_order_return_item_return FOREIGN KEY (return_id) REFERENCES order_return (id),
    CONSTRAINT ck_order_return_item_quantity CHECK (quantity > 0 AND refund_amount >= 0)
) ENGINE = InnoDB;
