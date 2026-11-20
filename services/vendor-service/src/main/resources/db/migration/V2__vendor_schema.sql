-- Vendor onboarding and approval lifecycle. IDs: UUIDv7 as BINARY(16).

CREATE TABLE vendor (
    id                  BINARY(16)    NOT NULL,
    owner_user_id       BINARY(16)    NOT NULL,          -- identity user who registered as VENDOR
    status              VARCHAR(20)   NOT NULL,          -- DRAFT | PENDING_APPROVAL | APPROVED | REJECTED | SUSPENDED
    business_name       VARCHAR(150)  NULL,
    legal_name          VARCHAR(200)  NULL,
    gstin               VARCHAR(15)   NULL,
    fssai_license       VARCHAR(14)   NULL,
    contact_email       VARCHAR(254)  NULL,
    contact_phone       VARCHAR(20)   NULL,
    address_line1       VARCHAR(200)  NULL,
    address_line2       VARCHAR(200)  NULL,
    address_city        VARCHAR(100)  NULL,
    address_state       VARCHAR(100)  NULL,
    address_postal_code VARCHAR(10)   NULL,
    address_country     VARCHAR(2)    NULL,
    address_latitude    DOUBLE        NULL,             -- pickup location (delivery assignment, geo search)
    address_longitude   DOUBLE        NULL,
    status_reason       VARCHAR(500)  NULL,             -- latest rejection / suspension reason
    submitted_at        DATETIME(6)   NULL,
    approved_at         DATETIME(6)   NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_vendor PRIMARY KEY (id),
    CONSTRAINT uk_vendor_owner UNIQUE (owner_user_id),
    INDEX idx_vendor_status_submitted (status, submitted_at),
    INDEX idx_vendor_updated (updated_at)
) ENGINE = InnoDB;

-- Who changed a vendor's status, when and why (admin decisions are auditable).
CREATE TABLE vendor_status_change (
    id           BINARY(16)   NOT NULL,
    vendor_id    BINARY(16)   NOT NULL,
    from_status  VARCHAR(20)  NULL,
    to_status    VARCHAR(20)  NOT NULL,
    reason       VARCHAR(500) NULL,
    changed_by   BINARY(16)   NULL,
    changed_at   DATETIME(6)  NOT NULL,
    CONSTRAINT pk_vendor_status_change PRIMARY KEY (id),
    CONSTRAINT fk_vendor_status_change_vendor FOREIGN KEY (vendor_id) REFERENCES vendor (id) ON DELETE CASCADE,
    INDEX idx_vendor_status_change_vendor (vendor_id, changed_at)
) ENGINE = InnoDB;
