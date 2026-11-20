-- Product catalog write model. IDs: UUIDv7 as BINARY(16); timestamps DATETIME(6) in UTC.
-- The database collation (utf8mb4_0900_ai_ci) is case-insensitive, so the unique keys on names and SKUs
-- are case-insensitive too.

-- Category tree, at most 3 levels (root depth = 1).
CREATE TABLE category (
    id                BINARY(16)    NOT NULL,
    parent_id         BINARY(16)    NULL,
    -- NULL is never equal to NULL in a unique key, so roots get a fixed all-zero key for the sibling rule.
    parent_key        BINARY(16)    GENERATED ALWAYS AS (COALESCE(parent_id, 0x00000000000000000000000000000000)) STORED,
    name              VARCHAR(80)   NOT NULL,
    description       VARCHAR(500)  NULL,
    image_url         VARCHAR(500)  NULL,
    sort_order        INT           NOT NULL DEFAULT 0,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    depth             TINYINT       NOT NULL,
    category_version  BIGINT        NOT NULL DEFAULT 0,  -- CategoryChanged.categoryVersion (search external version)
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id),
    CONSTRAINT uk_category_sibling_name UNIQUE (parent_key, name)
) ENGINE = InnoDB;

-- Shared brand dictionary; vendors pick an existing brand by name or create one on the fly.
CREATE TABLE brand (
    id          BINARY(16)   NOT NULL,
    name        VARCHAR(80)  NOT NULL,
    name_key    VARCHAR(80)  NOT NULL,   -- lower-case, whitespace collapsed, only [a-z0-9 ]
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_brand PRIMARY KEY (id),
    CONSTRAINT uk_brand_name_key UNIQUE (name_key)
) ENGINE = InnoDB;

-- Local projection of vendor.events: who sells, under which name, and whether they are suspended.
CREATE TABLE vendor_ref (
    vendor_id      BINARY(16)    NOT NULL,
    owner_user_id  BINARY(16)    NULL,
    business_name  VARCHAR(150)  NULL,
    status         VARCHAR(20)   NOT NULL,   -- DRAFT | PENDING_APPROVAL | APPROVED | REJECTED | SUSPENDED
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_vendor_ref PRIMARY KEY (vendor_id)
) ENGINE = InnoDB;

CREATE TABLE product (
    id               BINARY(16)     NOT NULL,
    vendor_id        BINARY(16)     NOT NULL,
    -- NULL only for ARCHIVED products whose category was deleted afterwards (a category with
    -- non-archived products cannot be deleted).
    category_id      BINARY(16)     NULL,
    brand_id         BINARY(16)     NULL,
    name             VARCHAR(150)   NOT NULL,
    description      VARCHAR(2000)  NULL,
    unit_value       DECIMAL(10, 3) NOT NULL,
    unit_of_measure  VARCHAR(10)    NOT NULL,   -- G | KG | ML | L | PCS | PACK
    sku              VARCHAR(40)    NULL,
    -- The SKU only counts as taken while the product is not archived.
    sku_key          VARCHAR(40)    GENERATED ALWAYS AS (IF(status <> 'ARCHIVED', sku, NULL)) STORED,
    barcode          VARCHAR(14)    NULL,
    food_type        VARCHAR(10)    NULL,       -- VEG | NON_VEG | EGG
    status           VARCHAR(12)    NOT NULL,   -- DRAFT | PUBLISHED | UNPUBLISHED | ARCHIVED
    product_version  BIGINT         NOT NULL DEFAULT 0,  -- ProductUpserted.productVersion (search external version)
    published_at     DATETIME(6)    NULL,
    archived_at      DATETIME(6)    NULL,
    created_at       DATETIME(6)    NOT NULL,
    updated_at       DATETIME(6)    NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE SET NULL,
    CONSTRAINT fk_product_brand FOREIGN KEY (brand_id) REFERENCES brand (id),
    CONSTRAINT uk_product_vendor_sku UNIQUE (vendor_id, sku_key),
    INDEX idx_product_vendor_status_updated (vendor_id, status, updated_at),
    INDEX idx_product_category_status (category_id, status),
    INDEX idx_product_brand (brand_id)
) ENGINE = InnoDB;

-- Ordered value lists of a product (first image = primary image).
CREATE TABLE product_image (
    product_id  BINARY(16)    NOT NULL,
    sort_order  INT           NOT NULL,
    url         VARCHAR(500)  NOT NULL,
    CONSTRAINT pk_product_image PRIMARY KEY (product_id, sort_order),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE product_attribute (
    product_id  BINARY(16)    NOT NULL,
    sort_order  INT           NOT NULL,
    name        VARCHAR(50)   NOT NULL,
    value       VARCHAR(200)  NOT NULL,
    CONSTRAINT pk_product_attribute PRIMARY KEY (product_id, sort_order),
    CONSTRAINT fk_product_attribute_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE product_tag (
    product_id  BINARY(16)    NOT NULL,
    sort_order  INT           NOT NULL,
    tag         VARCHAR(30)   NOT NULL,
    CONSTRAINT pk_product_tag PRIMARY KEY (product_id, sort_order),
    CONSTRAINT fk_product_tag_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE = InnoDB;
