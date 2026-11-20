-- Phase 8: the restaurant's view of its RESTAURANT orders (docs/api/phase-8-vendor-and-restaurant-fulfillment.md §2, §3).
-- Created from order-service's OrderConfirmed (a SNAPSHOT of what the kitchen needs: lines, totals, the customer's
-- name and area, the note); only the status columns move on afterwards. IDs as BINARY(16), money DECIMAL(12,2) INR,
-- timestamps DATETIME(6) UTC.
-- Locking rule (service/StoreOrderService.java): every status change first takes SELECT … FOR UPDATE on the
-- restaurant_order row, then checks and changes it, appends its history row and writes its outbox event, in one
-- transaction.

CREATE TABLE restaurant_order (
    order_id             BINARY(16)     NOT NULL,   -- = order-service's order id (one store order per order)
    order_number         VARCHAR(20)    NOT NULL,   -- "GA-7K3F9Q2M"
    restaurant_id        BINARY(16)     NOT NULL,   -- the order's storeId
    customer_id          BINARY(16)     NOT NULL,
    status               VARCHAR(20)    NOT NULL,   -- NEW | ACCEPTED | PREPARING | READY_FOR_PICKUP | REJECTED | CANCELLED
    item_count           INT            NOT NULL,   -- units ordered (sum of quantities)
    subtotal             DECIMAL(12,2)  NOT NULL,   -- the restaurant's sales (sum of line totals, no delivery fee)
    total                DECIMAL(12,2)  NOT NULL,   -- what the customer paid
    currency             VARCHAR(3)     NOT NULL,
    customer_name        VARCHAR(100)   NULL,       -- the delivery address' recipient name
    customer_area        VARCHAR(300)   NULL,       -- landmark or line2, and city — never the phone or full address
    note                 VARCHAR(300)   NULL,       -- the customer's note
    confirmed_at         DATETIME(6)    NULL,       -- when the order was paid (OrderConfirmed)
    received_at          DATETIME(6)    NOT NULL,   -- when this service created the store order
    accept_by            DATETIME(6)    NOT NULL,   -- received_at + grocery.fulfillment.acceptance-timeout
    accepted_at          DATETIME(6)    NULL,       -- set once: the order reached ACCEPTED (also when cancelled later)
    ready_in_minutes     INT            NULL,       -- the kitchen's preparation time
    ready_by             DATETIME(6)    NULL,
    preparing_at         DATETIME(6)    NULL,
    ready_at             DATETIME(6)    NULL,
    -- rejection (status REJECTED)
    rejection_reason     VARCHAR(20)    NULL,       -- OUT_OF_STOCK | STORE_CLOSED | TOO_BUSY | OTHER | NO_RESPONSE
    rejection_note       VARCHAR(300)   NULL,
    rejection_auto       BOOLEAN        NULL,       -- true = auto-rejected at the deadline
    rejected_at          DATETIME(6)    NULL,
    rejected_by          BINARY(16)     NULL,       -- the restaurant user; NULL when automatic
    -- cancellation (status CANCELLED, from OrderCancelled)
    cancellation_reason  VARCHAR(40)    NULL,       -- CUSTOMER_CANCELLED | ADMIN_CANCELLED | …
    cancelled_by         VARCHAR(20)    NULL,       -- CUSTOMER | ADMIN | STORE | SYSTEM
    cancelled_at         DATETIME(6)    NULL,
    updated_at           DATETIME(6)    NOT NULL,
    version              BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_restaurant_order PRIMARY KEY (order_id),
    CONSTRAINT ck_restaurant_order_amounts CHECK (subtotal >= 0 AND total >= 0 AND item_count > 0),
    INDEX idx_restaurant_order_board (restaurant_id, status, accept_by),     -- the board: one store, by status, deadline
    INDEX idx_restaurant_order_deadline (status, accept_by),                 -- the auto-reject poller: NEW past accept_by
    INDEX idx_restaurant_order_received (restaurant_id, received_at)         -- the sales report: one store, a date range
) ENGINE = InnoDB;

-- The order's lines (snapshot, never changed), in the order's line order.
CREATE TABLE restaurant_order_item (
    order_id     BINARY(16)     NOT NULL,
    line_no      INT            NOT NULL,
    item_id      BINARY(16)     NOT NULL,   -- menuItemId
    item_type    VARCHAR(10)    NULL,       -- MENU_ITEM
    name         VARCHAR(200)   NOT NULL,
    quantity     INT            NOT NULL,
    unit_price   DECIMAL(12,2)  NOT NULL,
    line_total   DECIMAL(12,2)  NOT NULL,
    CONSTRAINT pk_restaurant_order_item PRIMARY KEY (order_id, line_no),
    CONSTRAINT fk_restaurant_order_item_order FOREIGN KEY (order_id) REFERENCES restaurant_order (order_id),
    CONSTRAINT ck_restaurant_order_item_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;

-- Every status the store order went through (the timeline), oldest first. Append-only.
CREATE TABLE restaurant_order_status_history (
    order_id       BINARY(16)   NOT NULL,
    seq            INT          NOT NULL,   -- 0, 1, 2 … per order
    status         VARCHAR(20)  NOT NULL,
    reason         VARCHAR(40)  NULL,       -- rejection / cancellation reason
    actor_user_id  BINARY(16)   NULL,       -- the restaurant user; NULL for the system (received, auto-reject, cancellation)
    changed_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_restaurant_order_status_history PRIMARY KEY (order_id, seq),
    CONSTRAINT fk_restaurant_order_status_history_order FOREIGN KEY (order_id) REFERENCES restaurant_order (order_id)
) ENGINE = InnoDB;
