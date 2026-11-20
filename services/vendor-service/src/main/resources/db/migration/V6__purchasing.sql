-- Phase 12 §4: vendor purchasing & supply (docs/api/phase-12-post-mvp-features.md §4). IDs as BINARY(16), money
-- DECIMAL(12,2) INR, timestamps DATETIME(6) UTC.
-- Locking rule (service/PurchaseOrderService.java): every purchase-order change first takes SELECT … FOR UPDATE on the
-- purchase_order row; creating or editing a purchase order reads its supplier with FOR SHARE, deactivating a supplier
-- takes FOR UPDATE on it before counting its open purchase orders — so a supplier is never deactivated under a new one.

-- The vendor's products, a copy of catalog.events → ProductUpserted (consumer group vendor-service.catalog-events).
-- Updated only by a newer productVersion; purchase order lines may only name the caller's own, non-archived products.
CREATE TABLE product_ref (
    product_id       BINARY(16)    NOT NULL,
    vendor_id        BINARY(16)    NOT NULL,
    name             VARCHAR(150)  NOT NULL,
    unit_label       VARCHAR(40)   NULL,
    sku              VARCHAR(40)   NULL,
    status           VARCHAR(12)   NOT NULL,   -- DRAFT | PUBLISHED | UNPUBLISHED | ARCHIVED
    product_version  BIGINT        NOT NULL,   -- ProductUpserted.productVersion of the stored copy
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT pk_product_ref PRIMARY KEY (product_id),
    INDEX idx_product_ref_vendor_status_name (vendor_id, status, name)
) ENGINE = InnoDB;

-- A vendor's suppliers. "Delete" = deactivate (refused while the supplier has open purchase orders).
CREATE TABLE supplier (
    id              BINARY(16)    NOT NULL,
    vendor_id       BINARY(16)    NOT NULL,
    name            VARCHAR(120)  NOT NULL,
    contact_name    VARCHAR(100)  NULL,
    phone           VARCHAR(20)   NULL,
    email           VARCHAR(254)  NULL,
    gstin           VARCHAR(15)   NULL,
    address         VARCHAR(300)  NULL,          -- free text
    lead_time_days  INT           NULL,          -- 0–90
    notes           VARCHAR(500)  NULL,
    active          BOOLEAN       NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_supplier PRIMARY KEY (id),
    CONSTRAINT ck_supplier_lead_time CHECK (lead_time_days IS NULL OR lead_time_days BETWEEN 0 AND 90),
    INDEX idx_supplier_vendor_name (vendor_id, active, name)
) ENGINE = InnoDB;

-- Purchase orders: DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED; DRAFT / SENT → CANCELLED; PARTIALLY_RECEIVED → CLOSED.
CREATE TABLE purchase_order (
    id                 BINARY(16)    NOT NULL,
    number             VARCHAR(11)   NOT NULL,   -- "PO-7K3M9QX2"
    vendor_id          BINARY(16)    NOT NULL,
    supplier_id        BINARY(16)    NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    expected_at        DATETIME(6)   NULL,
    notes              VARCHAR(500)  NULL,
    created_by         BINARY(16)    NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    sent_at            DATETIME(6)   NULL,
    cancelled_at       DATETIME(6)   NULL,
    closed_at          DATETIME(6)   NULL,
    fully_received_at  DATETIME(6)   NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_purchase_order PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_number UNIQUE (number),
    CONSTRAINT fk_purchase_order_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id),
    INDEX idx_purchase_order_vendor_created (vendor_id, status, created_at),
    INDEX idx_purchase_order_supplier_status (supplier_id, status),
    INDEX idx_purchase_order_created (created_at)
) ENGINE = InnoDB;

-- The lines in the order the vendor entered them; replaced as a whole while the purchase order is a DRAFT, afterwards
-- only received_quantity moves (0 ≤ received ≤ ordered).
CREATE TABLE purchase_order_line (
    purchase_order_id  BINARY(16)     NOT NULL,
    line_no            INT            NOT NULL,
    product_id         BINARY(16)     NOT NULL,
    product_name       VARCHAR(150)   NOT NULL,  -- snapshot from product_ref when the line was written
    ordered_quantity   INT            NOT NULL,
    unit_cost          DECIMAL(12,2)  NOT NULL,
    received_quantity  INT            NOT NULL DEFAULT 0,
    CONSTRAINT pk_purchase_order_line PRIMARY KEY (purchase_order_id, line_no),
    CONSTRAINT fk_purchase_order_line_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT ck_purchase_order_line_quantities CHECK (ordered_quantity BETWEEN 1 AND 100000
        AND received_quantity >= 0 AND received_quantity <= ordered_quantity AND unit_cost >= 0)
) ENGINE = InnoDB;

-- Goods receipts: one per successful POST …/receive (Idempotency-Key), each published as GoodsReceived. Append-only.
CREATE TABLE purchase_receipt (
    id                 BINARY(16)    NOT NULL,
    purchase_order_id  BINARY(16)    NOT NULL,
    note               VARCHAR(300)  NULL,
    received_by        BINARY(16)    NOT NULL,
    received_at        DATETIME(6)   NOT NULL,
    CONSTRAINT pk_purchase_receipt PRIMARY KEY (id),
    CONSTRAINT fk_purchase_receipt_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    INDEX idx_purchase_receipt_order (purchase_order_id, received_at)
) ENGINE = InnoDB;

CREATE TABLE purchase_receipt_line (
    receipt_id    BINARY(16)     NOT NULL,
    line_no       INT            NOT NULL,
    product_id    BINARY(16)     NOT NULL,
    product_name  VARCHAR(150)   NOT NULL,
    quantity      INT            NOT NULL,
    unit_cost     DECIMAL(12,2)  NOT NULL,   -- the purchase order line's unit cost
    CONSTRAINT pk_purchase_receipt_line PRIMARY KEY (receipt_id, line_no),
    CONSTRAINT fk_purchase_receipt_line_receipt FOREIGN KEY (receipt_id) REFERENCES purchase_receipt (id),
    CONSTRAINT ck_purchase_receipt_line_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;

-- Idempotency-Key of POST /api/vendor/purchase-orders/{id}/receive (grocery-common IdempotencyKeys): scope =
-- "purchase-receipt:<vendorId>", result_ref = the receipt id. Claimed in the same transaction that writes the receipt.
CREATE TABLE idempotency_key (
    scope         VARCHAR(100)  NOT NULL,
    idem_key      VARCHAR(100)  NOT NULL,
    request_hash  CHAR(64)      NOT NULL,
    result_ref    VARCHAR(100)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    CONSTRAINT pk_idempotency_key PRIMARY KEY (scope, idem_key)
) ENGINE = InnoDB;
