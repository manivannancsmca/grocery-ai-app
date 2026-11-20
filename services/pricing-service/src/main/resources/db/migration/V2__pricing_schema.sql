-- Pricing model. IDs: UUIDv7 as BINARY(16); timestamps DATETIME(6) in UTC; money DECIMAL(12,2) in INR.
-- Locking rule (see PriceWriter.java): every transaction that changes a live price or a price-change request
-- first locks the product's product_ref row with SELECT ... FOR UPDATE, before it reads anything else.

-- Local copy of the catalog's products, built from catalog.events -> ProductUpserted (only what pricing needs).
-- The row exists before the first price, so it is also the lock that serialises every price change of a product.
CREATE TABLE product_ref (
    product_id       BINARY(16)    NOT NULL,
    vendor_id        BINARY(16)    NOT NULL,
    vendor_name      VARCHAR(150)  NULL,
    name             VARCHAR(150)  NOT NULL,
    unit_label       VARCHAR(40)   NULL,
    sku              VARCHAR(40)   NULL,
    status           VARCHAR(12)   NOT NULL,   -- DRAFT | PUBLISHED | UNPUBLISHED | ARCHIVED
    product_version  BIGINT        NOT NULL,   -- ProductUpserted.productVersion: only newer snapshots are applied
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT pk_product_ref PRIMARY KEY (product_id),
    INDEX idx_product_ref_vendor_status_name (vendor_id, status, name)
) ENGINE = InnoDB;

-- The live price: at most one per product.
CREATE TABLE product_price (
    product_id     BINARY(16)     NOT NULL,
    vendor_id      BINARY(16)     NOT NULL,
    mrp            DECIMAL(12, 2) NOT NULL,
    selling_price  DECIMAL(12, 2) NOT NULL,
    currency       CHAR(3)        NOT NULL DEFAULT 'INR',
    price_version  BIGINT         NOT NULL,   -- PriceChanged.priceVersion: +1 on every change, 1 = first price
    updated_by     BINARY(16)     NOT NULL,
    updated_at     DATETIME(6)    NOT NULL,
    CONSTRAINT pk_product_price PRIMARY KEY (product_id),
    CONSTRAINT fk_product_price_product FOREIGN KEY (product_id) REFERENCES product_ref (product_id),
    CONSTRAINT ck_product_price_range CHECK (selling_price >= 1.00 AND selling_price <= mrp AND mrp <= 1000000.00),
    INDEX idx_product_price_vendor (vendor_id)
) ENGINE = InnoDB;

-- Price-change requests above the approval threshold. At most one PENDING request per product: pending_key is
-- the product id while the request is pending and NULL otherwise (NULLs never collide in a unique key).
CREATE TABLE price_change_request (
    id                       BINARY(16)     NOT NULL,
    product_id               BINARY(16)     NOT NULL,
    vendor_id                BINARY(16)     NOT NULL,
    current_mrp              DECIMAL(12, 2) NOT NULL,
    current_selling_price    DECIMAL(12, 2) NOT NULL,
    requested_mrp            DECIMAL(12, 2) NOT NULL,
    requested_selling_price  DECIMAL(12, 2) NOT NULL,
    -- (requested - current) / current * 100 on the selling price, signed. 10 digits: 1.00 -> 1000000.00 is +99999900 %.
    change_percent           DECIMAL(10, 2) NOT NULL,
    status                   VARCHAR(10)    NOT NULL,   -- PENDING | APPROVED | REJECTED | CANCELLED
    pending_key              BINARY(16)     GENERATED ALWAYS AS (IF(status = 'PENDING', product_id, NULL)) STORED,
    note                     VARCHAR(300)   NULL,
    requested_by             BINARY(16)     NOT NULL,
    requested_at             DATETIME(6)    NOT NULL,
    decided_by               BINARY(16)     NULL,       -- NULL for an automatic cancellation (product archived)
    decided_at               DATETIME(6)    NULL,
    decision_reason          VARCHAR(300)   NULL,
    version                  BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_price_change_request PRIMARY KEY (id),
    CONSTRAINT fk_price_change_request_product FOREIGN KEY (product_id) REFERENCES product_ref (product_id),
    CONSTRAINT uk_price_change_request_pending UNIQUE (pending_key),
    INDEX idx_price_change_request_status_requested (status, requested_at),
    INDEX idx_price_change_request_vendor_requested (vendor_id, requested_at),
    INDEX idx_price_change_request_product (product_id, requested_at)
) ENGINE = InnoDB;

-- Every change of a live price (append-only).
CREATE TABLE price_history (
    id                 BINARY(16)     NOT NULL,
    product_id         BINARY(16)     NOT NULL,
    vendor_id          BINARY(16)     NOT NULL,
    old_mrp            DECIMAL(12, 2) NULL,       -- NULL for the first price
    old_selling_price  DECIMAL(12, 2) NULL,
    new_mrp            DECIMAL(12, 2) NOT NULL,
    new_selling_price  DECIMAL(12, 2) NOT NULL,
    change_percent     DECIMAL(10, 2) NULL,       -- NULL for the first price
    source             VARCHAR(20)    NOT NULL,   -- VENDOR | APPROVED_REQUEST
    request_id         BINARY(16)     NULL,
    changed_by         BINARY(16)     NOT NULL,
    changed_at         DATETIME(6)    NOT NULL,
    CONSTRAINT pk_price_history PRIMARY KEY (id),
    CONSTRAINT fk_price_history_product FOREIGN KEY (product_id) REFERENCES product_ref (product_id),
    CONSTRAINT fk_price_history_request FOREIGN KEY (request_id) REFERENCES price_change_request (id),
    INDEX idx_price_history_product_changed (product_id, changed_at)
) ENGINE = InnoDB;
