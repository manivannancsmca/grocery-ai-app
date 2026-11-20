-- Delivery partner onboarding and approval lifecycle. IDs: UUIDv7 as BINARY(16).
-- A partner's id IS the identity user id (partnerId = userId), so there is no separate owner column.
-- Deliveries, offers and assignment (Phase 9) arrive in later migrations and reference delivery_partner(id).

CREATE TABLE delivery_partner (
    id                      BINARY(16)    NOT NULL,      -- = identity user who registered as DELIVERY_PARTNER
    status                  VARCHAR(20)   NOT NULL,      -- DRAFT | PENDING_APPROVAL | APPROVED | REJECTED | SUSPENDED
    full_name               VARCHAR(150)  NULL,
    email                   VARCHAR(254)  NULL,          -- from registration / JWT; not editable here
    phone                   VARCHAR(20)   NULL,
    city                    VARCHAR(100)  NULL,
    vehicle_type            VARCHAR(20)   NULL,          -- BICYCLE | SCOOTER | MOTORCYCLE | CAR
    vehicle_number          VARCHAR(15)   NULL,          -- normalised (no spaces/hyphens, upper case), e.g. KA01AB1234
    driving_license_number  VARCHAR(20)   NULL,          -- normalised; not required for BICYCLE
    status_reason           VARCHAR(500)  NULL,          -- latest rejection / suspension reason
    submitted_at            DATETIME(6)   NULL,
    approved_at             DATETIME(6)   NULL,
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_delivery_partner PRIMARY KEY (id),
    INDEX idx_delivery_partner_status_submitted (status, submitted_at),
    INDEX idx_delivery_partner_updated (updated_at)
) ENGINE = InnoDB;

-- Who changed a partner's status, when and why (admin / delivery-manager decisions are auditable).
CREATE TABLE delivery_partner_status_change (
    id           BINARY(16)   NOT NULL,
    partner_id   BINARY(16)   NOT NULL,
    from_status  VARCHAR(20)  NULL,
    to_status    VARCHAR(20)  NOT NULL,
    reason       VARCHAR(500) NULL,
    changed_by   BINARY(16)   NULL,
    changed_at   DATETIME(6)  NOT NULL,
    CONSTRAINT pk_delivery_partner_status_change PRIMARY KEY (id),
    CONSTRAINT fk_delivery_partner_status_change_partner FOREIGN KEY (partner_id) REFERENCES delivery_partner (id) ON DELETE CASCADE,
    INDEX idx_delivery_partner_status_change_partner (partner_id, changed_at)
) ENGINE = InnoDB;
