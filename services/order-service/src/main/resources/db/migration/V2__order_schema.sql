-- Order model. IDs: UUIDv7 as BINARY(16); money DECIMAL(12,2) in INR; timestamps DATETIME(6) in UTC.
-- Locking rule (see service/OrderLocks.java): every status change first takes SELECT … FOR UPDATE on the
-- customer_order row, then reads and changes the order, writes its history row and its outbox event(s), and commits.

-- One row per order: a SNAPSHOT of what the customer saw at checkout (lines, prices, fee, totals, delivery address).
-- It never changes when catalog prices change later; only the status, payment projection and cancellation /
-- rejection columns move on.
CREATE TABLE customer_order (
    id                        BINARY(16)     NOT NULL,
    order_number              VARCHAR(12)    NOT NULL,   -- "GA-" + 8 Crockford base32 characters, upper case
    customer_id               BINARY(16)     NOT NULL,
    type                      VARCHAR(10)    NOT NULL,   -- GROCERY | RESTAURANT (= the store type)
    status                    VARCHAR(20)    NOT NULL,   -- CREATED | AWAITING_PAYMENT | CONFIRMED | REJECTED | CANCELLED
    store_id                  BINARY(16)     NOT NULL,   -- vendorId (GROCERY) or restaurantId (RESTAURANT)
    store_name                VARCHAR(150)   NULL,
    item_count                INT            NOT NULL,   -- sum of quantities
    first_item_name           VARCHAR(200)   NULL,       -- for order lists (no join with the lines)
    subtotal                  DECIMAL(12,2)  NOT NULL,
    mrp_total                 DECIMAL(12,2)  NOT NULL,
    savings                   DECIMAL(12,2)  NOT NULL,
    delivery_fee              DECIMAL(12,2)  NOT NULL,
    total                     DECIMAL(12,2)  NOT NULL,
    currency                  VARCHAR(3)     NOT NULL,
    -- delivery address snapshot (customer-service address at checkout time)
    address_id                BINARY(16)     NULL,
    address_label             VARCHAR(40)    NULL,
    recipient_name            VARCHAR(100)   NULL,
    phone                     VARCHAR(20)    NULL,
    line1                     VARCHAR(200)   NULL,
    line2                     VARCHAR(200)   NULL,
    landmark                  VARCHAR(200)   NULL,
    city                      VARCHAR(100)   NULL,
    state                     VARCHAR(100)   NULL,
    postal_code               VARCHAR(20)    NULL,
    latitude                  DOUBLE         NULL,
    longitude                 DOUBLE         NULL,
    delivery_distance_km      DECIMAL(6,1)   NULL,
    note                      VARCHAR(300)   NULL,       -- delivery instructions
    cart_updated_at           DATETIME(6)    NULL,       -- the cart version the order was placed from
    -- order-service's own view of the payment, kept from payment.events
    payment_status            VARCHAR(20)    NOT NULL,   -- NOT_STARTED | PENDING | PAID | EXPIRED | NOT_REQUIRED | REFUND_PENDING | REFUNDED | PARTIALLY_REFUNDED
    payment_id                BINARY(16)     NULL,
    payment_due_at            DATETIME(6)    NOT NULL,   -- created_at + grocery.order.payment-window
    paid_at                   DATETIME(6)    NULL,
    refunded_amount           DECIMAL(12,2)  NOT NULL DEFAULT 0.00,
    last_failure_code         VARCHAR(100)   NULL,
    last_failure_description  VARCHAR(500)   NULL,
    last_failure_at           DATETIME(6)    NULL,
    -- cancellation (status CANCELLED)
    cancellation_reason       VARCHAR(30)    NULL,       -- CUSTOMER_CANCELLED | ADMIN_CANCELLED | PAYMENT_TIMEOUT | RESERVATION_EXPIRED
    cancellation_note         VARCHAR(300)   NULL,
    cancelled_by              VARCHAR(10)    NULL,       -- CUSTOMER | ADMIN | SYSTEM
    cancelled_by_user_id      BINARY(16)     NULL,
    cancelled_at              DATETIME(6)    NULL,
    -- rejection (status REJECTED); the unavailable lines are in order_rejected_item
    rejection_reason          VARCHAR(30)    NULL,       -- OUT_OF_STOCK
    confirmed_at              DATETIME(6)    NULL,
    created_at                DATETIME(6)    NOT NULL,
    updated_at                DATETIME(6)    NOT NULL,
    version                   BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_customer_order PRIMARY KEY (id),
    CONSTRAINT uk_customer_order_number UNIQUE (order_number),
    CONSTRAINT ck_customer_order_amounts CHECK (subtotal >= 0 AND delivery_fee >= 0 AND total >= 0
                                                AND refunded_amount >= 0 AND item_count > 0),
    INDEX idx_customer_order_customer_created (customer_id, created_at),
    INDEX idx_customer_order_created (created_at),
    INDEX idx_customer_order_status_created (status, created_at),
    INDEX idx_customer_order_store_created (store_id, created_at),
    INDEX idx_customer_order_confirmed (confirmed_at)
) ENGINE = InnoDB;

-- The order's lines (snapshot, never changed after creation), in cart order.
CREATE TABLE order_item (
    order_id     BINARY(16)     NOT NULL,
    line_no      INT            NOT NULL,
    item_id      BINARY(16)     NOT NULL,   -- productId (PRODUCT) or menuItemId (MENU_ITEM)
    type         VARCHAR(10)    NOT NULL,   -- PRODUCT | MENU_ITEM
    name         VARCHAR(200)   NOT NULL,
    unit_label   VARCHAR(40)    NULL,
    image_url    VARCHAR(500)   NULL,
    food_type    VARCHAR(20)    NULL,
    quantity     INT            NOT NULL,
    unit_price   DECIMAL(12,2)  NOT NULL,
    mrp          DECIMAL(12,2)  NULL,       -- grocery only
    line_total   DECIMAL(12,2)  NOT NULL,
    CONSTRAINT pk_order_item PRIMARY KEY (order_id, line_no),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;

-- Every status the order went through, oldest first (the order's timeline). Append-only.
CREATE TABLE order_status_history (
    order_id    BINARY(16)   NOT NULL,
    seq         INT          NOT NULL,   -- 0, 1, 2 … per order
    status      VARCHAR(20)  NOT NULL,
    reason      VARCHAR(30)  NULL,       -- rejection / cancellation reason
    changed_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_order_status_history PRIMARY KEY (order_id, seq),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES customer_order (id)
) ENGINE = InnoDB;

-- The lines inventory could not reserve (StockReservationFailed), for a REJECTED order.
CREATE TABLE order_rejected_item (
    order_id   BINARY(16)    NOT NULL,
    line_no    INT           NOT NULL,
    item_id    BINARY(16)    NOT NULL,
    name       VARCHAR(200)  NULL,
    requested  INT           NOT NULL,
    available  INT           NOT NULL,
    CONSTRAINT pk_order_rejected_item PRIMARY KEY (order_id, line_no),
    CONSTRAINT fk_order_rejected_item_order FOREIGN KEY (order_id) REFERENCES customer_order (id)
) ENGINE = InnoDB;

-- Idempotency-Key of POST /api/orders (grocery-common IdempotencyKeys): scope = "order:<customerId>",
-- result_ref = the order id. Claimed in the same transaction that creates the order.
CREATE TABLE idempotency_key (
    scope         VARCHAR(100)  NOT NULL,
    idem_key      VARCHAR(100)  NOT NULL,
    request_hash  CHAR(64)      NOT NULL,
    result_ref    VARCHAR(100)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    CONSTRAINT pk_idempotency_key PRIMARY KEY (scope, idem_key)
) ENGINE = InnoDB;
