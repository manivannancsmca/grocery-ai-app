-- Customer profile and delivery address book. IDs: UUIDv7 as BINARY(16).

-- One row per user who registered as CUSTOMER. customerId = userId (the JWT `sub`), so no mapping table.
CREATE TABLE customer (
    id          BINARY(16)    NOT NULL,          -- identity user id
    email       VARCHAR(254)  NOT NULL,          -- copy from identity (login email is owned there)
    full_name   VARCHAR(120)  NOT NULL,
    phone       VARCHAR(20)   NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_customer PRIMARY KEY (id),
    INDEX idx_customer_created (created_at)
) ENGINE = InnoDB;

-- Max 10 per customer; exactly one is_default = TRUE while the customer has any address
-- (enforced in AddressBookService under a lock on the customer row).
CREATE TABLE customer_address (
    id              BINARY(16)    NOT NULL,
    customer_id     BINARY(16)    NOT NULL,
    label           VARCHAR(50)   NOT NULL,      -- "Home", "Office", ...
    recipient_name  VARCHAR(120)  NOT NULL,
    phone           VARCHAR(20)   NOT NULL,
    line1           VARCHAR(200)  NOT NULL,
    line2           VARCHAR(200)  NULL,
    landmark        VARCHAR(200)  NULL,
    city            VARCHAR(100)  NOT NULL,
    state           VARCHAR(100)  NOT NULL,
    postal_code     VARCHAR(10)   NOT NULL,
    country         VARCHAR(2)    NOT NULL DEFAULT 'IN',
    latitude        DOUBLE        NULL,          -- drop location (delivery fee, partner assignment)
    longitude       DOUBLE        NULL,
    is_default      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_customer_address PRIMARY KEY (id),
    CONSTRAINT fk_customer_address_customer FOREIGN KEY (customer_id) REFERENCES customer (id) ON DELETE CASCADE,
    INDEX idx_customer_address_customer (customer_id, created_at)
) ENGINE = InnoDB;
