-- Inventory model. IDs: UUIDv7 as BINARY(16); timestamps DATETIME(6) in UTC.
-- Locking rule (see service/StockLocks.java): every stock change first takes SELECT … FOR UPDATE on the
-- stock_item row(s), several products in ascending product_id order, before reading anything else.

-- Local copy of catalog.events → ProductUpserted (only what inventory screens and events need).
-- Updated only by a newer productVersion; ARCHIVED products are kept (read-only, hidden from lists).
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

-- One stock row per product, created (0 on hand) when the product is first seen. The CHECK constraints are
-- the database's own guarantee that stock is never oversold: 0 ≤ reserved ≤ on_hand ≤ 1,000,000.
CREATE TABLE stock_item (
    product_id           BINARY(16)   NOT NULL,
    vendor_id            BINARY(16)   NOT NULL,
    on_hand              INT          NOT NULL DEFAULT 0,
    reserved             INT          NOT NULL DEFAULT 0,
    low_stock_threshold  INT          NOT NULL DEFAULT 5,     -- 0 = no low-stock alerts
    stock_version        BIGINT       NOT NULL DEFAULT 0,     -- +1 on every stock change; carried by the events
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    CONSTRAINT pk_stock_item PRIMARY KEY (product_id),
    CONSTRAINT fk_stock_item_product FOREIGN KEY (product_id) REFERENCES product_ref (product_id),
    CONSTRAINT ck_stock_item_reserved_not_negative CHECK (reserved >= 0),
    CONSTRAINT ck_stock_item_on_hand_covers_reserved CHECK (on_hand >= reserved),
    CONSTRAINT ck_stock_item_on_hand_max CHECK (on_hand <= 1000000),
    CONSTRAINT ck_stock_item_threshold CHECK (low_stock_threshold BETWEEN 0 AND 100000),
    INDEX idx_stock_item_vendor (vendor_id)
) ENGINE = InnoDB;

-- Append-only ledger: one row per stock change, with the resulting quantities.
CREATE TABLE stock_movement (
    id              BINARY(16)    NOT NULL,
    product_id      BINARY(16)    NOT NULL,
    vendor_id       BINARY(16)    NOT NULL,
    type            VARCHAR(10)   NOT NULL,   -- INCREASE | DECREASE | SET | RESERVE | RELEASE | COMMIT
    quantity_delta  INT           NOT NULL,   -- change of on hand (0 for RESERVE / RELEASE)
    on_hand_after   INT           NOT NULL,
    reserved_after  INT           NOT NULL,
    reason          VARCHAR(20)   NULL,       -- RESTOCK | RETURN | DAMAGED | EXPIRED | STOCK_COUNT | CORRECTION | OTHER
    note            VARCHAR(300)  NULL,
    reference       VARCHAR(64)   NULL,       -- order id for RESERVE / RELEASE / COMMIT
    actor_user_id   BINARY(16)    NULL,       -- NULL = system (order saga)
    created_at      DATETIME(6)   NOT NULL,
    CONSTRAINT pk_stock_movement PRIMARY KEY (id),
    INDEX idx_stock_movement_product_created (product_id, created_at)
) ENGINE = InnoDB;

-- Stock held for an order (one reservation per order; the lines never change after creation).
CREATE TABLE stock_reservation (
    id          BINARY(16)   NOT NULL,
    order_id    BINARY(16)   NOT NULL,
    status      VARCHAR(10)  NOT NULL,   -- RESERVED | RELEASED | COMMITTED
    expires_at  DATETIME(6)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_stock_reservation PRIMARY KEY (id),
    CONSTRAINT uk_stock_reservation_order UNIQUE (order_id),
    INDEX idx_stock_reservation_status_expires (status, expires_at)
) ENGINE = InnoDB;

CREATE TABLE stock_reservation_item (
    reservation_id  BINARY(16)  NOT NULL,
    product_id      BINARY(16)  NOT NULL,
    vendor_id       BINARY(16)  NOT NULL,
    quantity        INT         NOT NULL,
    CONSTRAINT pk_stock_reservation_item PRIMARY KEY (reservation_id, product_id),
    CONSTRAINT fk_stock_reservation_item_reservation FOREIGN KEY (reservation_id) REFERENCES stock_reservation (id),
    CONSTRAINT ck_stock_reservation_item_quantity CHECK (quantity > 0),
    INDEX idx_stock_reservation_item_product (product_id)
) ENGINE = InnoDB;
