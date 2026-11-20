-- Phase 12 §7: the reporting read model, built ONLY from events (consumer group reporting-service.facts).
--
-- Design: events of different topics arrive in any order (order.events, payment.events, delivery.events are separate
-- partitions), so every table converges whatever the arrival order:
--   * one row per aggregate, written by idempotent upserts: each event fills only the columns it knows (timestamps:
--     the first value wins; descriptive fields: the latest non-null value wins);
--   * refunds, returns, deliveries, reviews and receipts are their own facts, joined to the orders at query time;
--   * status and the placement time of an order are DERIVED (generated columns) from the timestamps that are known.
-- UUIDs are BINARY(16) in the UUID's own byte order; times are UTC DATETIME(6); money DECIMAL(12,2) INR.
-- Rebuild = truncate every table below (and processed_event) and replay the topics from the start (README.md).

-- One row per order: OrderCreated / OrderConfirmed / OrderCancelled / OrderRejected / OrderDelivered /
-- OrderDeliveryFailed fill it, PaymentCaptured adds the capture (and may create the row first).
CREATE TABLE order_fact (
    order_id                 BINARY(16)     NOT NULL,
    order_number             VARCHAR(20)    NULL,
    order_type               VARCHAR(12)    NULL,      -- GROCERY | RESTAURANT
    customer_id              BINARY(16)     NULL,
    store_id                 BINARY(16)     NULL,      -- vendorId (GROCERY) / restaurantId (RESTAURANT)
    store_name               VARCHAR(200)   NULL,      -- at ordering time
    currency                 CHAR(3)        NULL,
    subtotal                 DECIMAL(12, 2) NULL,      -- sum of the line totals
    delivery_fee             DECIMAL(12, 2) NULL,      -- OrderCreated only
    discount                 DECIMAL(12, 2) NULL,      -- coupon discount on the items (null = none)
    delivery_fee_discount    DECIMAL(12, 2) NULL,      -- FREE_DELIVERY coupons (null = none)
    total                    DECIMAL(12, 2) NULL,      -- what the customer pays = the order's GMV
    coupon_code              VARCHAR(40)    NULL,
    created_at               DATETIME(6)    NULL,
    confirmed_at             DATETIME(6)    NULL,      -- paid
    cancelled_at             DATETIME(6)    NULL,
    cancel_reason            VARCHAR(40)    NULL,
    cancelled_by             VARCHAR(20)    NULL,
    cancelled_from_status    VARCHAR(30)    NULL,
    rejected_at              DATETIME(6)    NULL,      -- OrderRejected carries no time: its occurredAt header
    reject_reason            VARCHAR(40)    NULL,
    delivered_at             DATETIME(6)    NULL,
    delivery_failed_at       DATETIME(6)    NULL,
    delivery_failure_reason  VARCHAR(40)    NULL,
    partner_id               BINARY(16)     NULL,
    captured_amount          DECIMAL(12, 2) NULL,
    captured_at              DATETIME(6)    NULL,
    capture_late             BOOLEAN        NULL,      -- captured after expiry: refunded automatically (LATE_CAPTURE)
    -- the refunded / returned amounts are NOT stored here (a refund may arrive before its order): see order_summary
    -- the day an order counts on: when it was placed (falls back to the first known time if OrderCreated is missing)
    placed_at                DATETIME(6) GENERATED ALWAYS AS
                                 (COALESCE(created_at, confirmed_at, cancelled_at, rejected_at)) STORED,
    status                   VARCHAR(20) GENERATED ALWAYS AS (CASE
                                 WHEN cancelled_at IS NOT NULL THEN 'CANCELLED'
                                 WHEN rejected_at IS NOT NULL THEN 'REJECTED'
                                 WHEN delivery_failed_at IS NOT NULL THEN 'DELIVERY_FAILED'
                                 WHEN delivered_at IS NOT NULL THEN 'DELIVERED'
                                 WHEN confirmed_at IS NOT NULL THEN 'CONFIRMED'
                                 WHEN created_at IS NOT NULL THEN 'CREATED'
                                 ELSE NULL END) STORED,
    updated_at               DATETIME(6)    NOT NULL,
    CONSTRAINT pk_order_fact PRIMARY KEY (order_id),
    INDEX idx_order_fact_placed (placed_at),
    INDEX idx_order_fact_store (store_id, placed_at),
    INDEX idx_order_fact_customer (customer_id, placed_at),
    INDEX idx_order_fact_coupon (coupon_code, placed_at),
    INDEX idx_order_fact_delivery_failed (delivery_failed_at)
) ENGINE = InnoDB;

-- One row per order line (OrderCreated, replaced by OrderConfirmed's identical lines).
CREATE TABLE order_line_fact (
    order_id     BINARY(16)     NOT NULL,
    line_no      SMALLINT       NOT NULL,              -- position in the event's items array
    item_id      BINARY(16)     NOT NULL,              -- productId (PRODUCT) / menuItemId (MENU_ITEM)
    item_type    VARCHAR(12)    NULL,
    name         VARCHAR(200)   NULL,
    quantity     INT            NOT NULL,
    unit_price   DECIMAL(12, 2) NULL,
    line_total   DECIMAL(12, 2) NOT NULL,              -- before the coupon discount
    CONSTRAINT pk_order_line_fact PRIMARY KEY (order_id, line_no),
    INDEX idx_order_line_fact_item (item_id)
) ENGINE = InnoDB;

-- One row per refund (RefundCompleted), unique per refundId whatever the number of deliveries of the event.
CREATE TABLE refund_fact (
    refund_id    BINARY(16)     NOT NULL,
    order_id     BINARY(16)     NOT NULL,
    payment_id   BINARY(16)     NULL,
    return_id    BINARY(16)     NULL,                  -- reason RETURN (Phase 12)
    amount       DECIMAL(12, 2) NOT NULL,
    reason       VARCHAR(30)    NULL,                  -- ORDER_CANCELLED | LATE_CAPTURE | ADMIN | RETURN
    refunded_at  DATETIME(6)    NOT NULL,              -- the event's occurredAt (the payload carries no time)
    CONSTRAINT pk_refund_fact PRIMARY KEY (refund_id),
    INDEX idx_refund_fact_order (order_id),
    INDEX idx_refund_fact_time (refunded_at)
) ENGINE = InnoDB;

-- One row per approved return (ReturnApproved) and per returned line.
CREATE TABLE return_fact (
    return_id        BINARY(16)     NOT NULL,
    return_number    VARCHAR(20)    NULL,
    order_id         BINARY(16)     NOT NULL,
    order_type       VARCHAR(12)    NULL,
    store_id         BINARY(16)     NULL,
    customer_id      BINARY(16)     NULL,
    refund_amount    DECIMAL(12, 2) NOT NULL,
    restock          BOOLEAN        NULL,
    decided_by_role  VARCHAR(10)    NULL,              -- STORE | ADMIN
    approved_at      DATETIME(6)    NOT NULL,
    CONSTRAINT pk_return_fact PRIMARY KEY (return_id),
    INDEX idx_return_fact_order (order_id),
    INDEX idx_return_fact_time (approved_at)
) ENGINE = InnoDB;

CREATE TABLE return_line_fact (
    return_id      BINARY(16)     NOT NULL,
    line_no        SMALLINT       NOT NULL,
    item_id        BINARY(16)     NOT NULL,
    name           VARCHAR(200)   NULL,
    quantity       INT            NOT NULL,
    refund_amount  DECIMAL(12, 2) NOT NULL,
    CONSTRAINT pk_return_line_fact PRIMARY KEY (return_id, line_no),
    INDEX idx_return_line_fact_item (item_id)
) ENGINE = InnoDB;

-- One row per delivery (DeliveryPartnerAssigned, DeliveryPickedUp, DeliveryCompleted); the confirmation time comes
-- from order_fact at query time.
CREATE TABLE delivery_fact (
    delivery_id        BINARY(16)   NOT NULL,
    order_id           BINARY(16)   NOT NULL,
    order_number       VARCHAR(20)  NULL,
    partner_id         BINARY(16)   NULL,              -- the partner of the latest event
    assignments        INT          NOT NULL DEFAULT 0,
    first_assigned_at  DATETIME(6)  NULL,              -- confirm -> assigned
    assigned_at        DATETIME(6)  NULL,              -- latest assignment: assigned -> picked up
    picked_up_at       DATETIME(6)  NULL,
    delivered_at       DATETIME(6)  NULL,
    updated_at         DATETIME(6)  NOT NULL,
    CONSTRAINT pk_delivery_fact PRIMARY KEY (delivery_id),
    INDEX idx_delivery_fact_order (order_id),
    INDEX idx_delivery_fact_delivered (delivered_at)
) ENGINE = InnoDB;

-- One row per review (ReviewSubmitted; an edit updates rating and status).
CREATE TABLE review_fact (
    review_id           BINARY(16)   NOT NULL,
    target_type         VARCHAR(20)  NOT NULL,
    target_id           BINARY(16)   NOT NULL,
    store_id            BINARY(16)   NULL,
    customer_id         BINARY(16)   NULL,
    order_id            BINARY(16)   NULL,
    rating              TINYINT      NOT NULL,
    status              VARCHAR(12)  NOT NULL,         -- PUBLISHED | FLAGGED, at the latest submission
    flag_reason         VARCHAR(40)  NULL,
    submissions         INT          NOT NULL DEFAULT 1,
    first_submitted_at  DATETIME(6)  NOT NULL,
    last_submitted_at   DATETIME(6)  NOT NULL,
    CONSTRAINT pk_review_fact PRIMARY KEY (review_id),
    INDEX idx_review_fact_time (first_submitted_at)
) ENGINE = InnoDB;

-- One row per rated target (RatingSummaryUpdated), guarded by summary_version (an older summary never wins).
CREATE TABLE rating_fact (
    target_type      VARCHAR(20)   NOT NULL,           -- PRODUCT | MENU_ITEM | VENDOR | RESTAURANT | DELIVERY_PARTNER
    target_id        BINARY(16)    NOT NULL,
    average_rating   DECIMAL(3, 2) NOT NULL,
    rating_count     INT           NOT NULL,
    stars_1          INT           NOT NULL,
    stars_2          INT           NOT NULL,
    stars_3          INT           NOT NULL,
    stars_4          INT           NOT NULL,
    stars_5          INT           NOT NULL,
    summary_version  BIGINT        NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT pk_rating_fact PRIMARY KEY (target_type, target_id),
    INDEX idx_rating_fact_average (average_rating, rating_count)
) ENGINE = InnoDB;

-- One row per goods receipt (GoodsReceived) and per received line.
CREATE TABLE receipt_fact (
    receipt_id             BINARY(16)     NOT NULL,
    vendor_id              BINARY(16)     NOT NULL,
    purchase_order_id      BINARY(16)     NOT NULL,
    purchase_order_number  VARCHAR(20)    NULL,
    supplier_id            BINARY(16)     NULL,
    units                  INT            NOT NULL,    -- sum of the line quantities
    cost                   DECIMAL(14, 2) NOT NULL,    -- sum of quantity x unitCost
    received_at            DATETIME(6)    NOT NULL,
    CONSTRAINT pk_receipt_fact PRIMARY KEY (receipt_id),
    INDEX idx_receipt_fact_time (received_at),
    INDEX idx_receipt_fact_vendor (vendor_id, received_at)
) ENGINE = InnoDB;

CREATE TABLE receipt_line_fact (
    receipt_id  BINARY(16)     NOT NULL,
    line_no     SMALLINT       NOT NULL,
    product_id  BINARY(16)     NOT NULL,
    quantity    INT            NOT NULL,
    unit_cost   DECIMAL(12, 2) NOT NULL,
    CONSTRAINT pk_receipt_line_fact PRIMARY KEY (receipt_id, line_no)
) ENGINE = InnoDB;

-- One row per order with its refunded and returned amounts, joined at read time (always consistent, whatever the
-- arrival order of the events). For ad-hoc queries; the reports aggregate the facts themselves.
CREATE VIEW order_summary AS
SELECT o.order_id, o.order_number, o.order_type, o.customer_id, o.store_id, o.store_name, o.currency, o.subtotal,
       o.delivery_fee, o.discount, o.delivery_fee_discount, o.total, o.coupon_code, o.status, o.placed_at,
       o.created_at, o.confirmed_at, o.cancelled_at, o.rejected_at, o.delivered_at, o.delivery_failed_at,
       COALESCE((SELECT SUM(r.amount) FROM refund_fact r WHERE r.order_id = o.order_id), 0.00) AS refunded_amount,
       COALESCE((SELECT SUM(t.refund_amount) FROM return_fact t WHERE t.order_id = o.order_id), 0.00)
           AS returned_amount
FROM order_fact o;

-- Names for the reports, from the events that carry them (the newest event time wins): stores from the orders,
-- items from the order lines, partners from DeliveryPartnerAssigned.
CREATE TABLE store_dim (
    store_id    BINARY(16)    NOT NULL,
    store_type  VARCHAR(12)   NULL,                    -- GROCERY | RESTAURANT
    store_name  VARCHAR(200)  NOT NULL,
    name_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_store_dim PRIMARY KEY (store_id)
) ENGINE = InnoDB;

CREATE TABLE item_dim (
    item_id    BINARY(16)    NOT NULL,
    item_type  VARCHAR(12)   NULL,                     -- PRODUCT | MENU_ITEM
    store_id   BINARY(16)    NULL,
    name       VARCHAR(200)  NOT NULL,
    name_at    DATETIME(6)   NOT NULL,
    CONSTRAINT pk_item_dim PRIMARY KEY (item_id)
) ENGINE = InnoDB;

CREATE TABLE partner_dim (
    partner_id  BINARY(16)    NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    name_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_partner_dim PRIMARY KEY (partner_id)
) ENGINE = InnoDB;
