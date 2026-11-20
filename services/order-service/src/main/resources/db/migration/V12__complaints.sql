-- Complaints (docs/api/payment-and-support-agents.md §2): a customer complains about one of their orders (or about the
-- app in general); support admins start and resolve it with an answer the customer sees.
-- OPEN -> IN_PROGRESS -> RESOLVED, or OPEN -> RESOLVED; RESOLVED is final.
-- The order's number and store name are snapshotted, so the lists need no join. No events, no outbox.
-- Changes use optimistic locking only (version): two admins changing one complaint at once -> the second gets 409.
-- Expand-only: one new table.
CREATE TABLE complaint (
    id                BINARY(16)     NOT NULL,
    complaint_number  VARCHAR(12)    NOT NULL,   -- "CMP-" + 8 Crockford base32 characters
    customer_id       BINARY(16)     NOT NULL,   -- from the token, never from the request
    order_id          BINARY(16)     NULL,       -- one of the customer's own orders; NULL = about the app in general
    order_number      VARCHAR(12)    NULL,       -- copied from the order
    store_name        VARCHAR(150)   NULL,       -- copied from the order
    category          VARCHAR(20)    NOT NULL,   -- DELIVERY | PAYMENT | ITEMS | STORE | APP | OTHER
    description       VARCHAR(1000)  NOT NULL,   -- the customer's words (10-1000 characters, trimmed)
    status            VARCHAR(20)    NOT NULL,   -- OPEN | IN_PROGRESS | RESOLVED
    resolution        VARCHAR(1000)  NULL,       -- the answer the customer sees (5-1000 characters), set when resolved
    handled_by        BINARY(16)     NULL,       -- the admin who started or resolved it
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NOT NULL,
    resolved_at       DATETIME(6)    NULL,
    version           BIGINT         NOT NULL DEFAULT 0,
    -- one open complaint (OPEN / IN_PROGRESS) per order at a time: NULL for a resolved one and without an order
    open_order_id     BINARY(16)     AS (CASE WHEN status IN ('OPEN', 'IN_PROGRESS') THEN order_id END) STORED,
    CONSTRAINT pk_complaint PRIMARY KEY (id),
    CONSTRAINT uk_complaint_number UNIQUE (complaint_number),
    CONSTRAINT uk_complaint_open_order UNIQUE (open_order_id),
    CONSTRAINT fk_complaint_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
    INDEX idx_complaint_order_status (order_id, status),               -- the open complaint of an order
    INDEX idx_complaint_customer_status (customer_id, status),         -- the open complaints of a customer (limit)
    INDEX idx_complaint_customer_created (customer_id, created_at),    -- the customer's list, newest first
    INDEX idx_complaint_status_created (status, created_at),           -- the admin queue per status, newest first
    INDEX idx_complaint_created (created_at),                          -- the admin list across statuses
    INDEX idx_complaint_order_number (order_number)                    -- the admin search by order number
) ENGINE = InnoDB;
