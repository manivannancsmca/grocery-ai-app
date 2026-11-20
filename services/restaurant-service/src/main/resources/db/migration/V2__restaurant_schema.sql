-- Restaurant onboarding and approval lifecycle. IDs: UUIDv7 as BINARY(16).
-- Menus (Phase 3) arrive in later migrations and reference restaurant(id).

CREATE TABLE restaurant (
    id                  BINARY(16)    NOT NULL,
    owner_user_id       BINARY(16)    NOT NULL,          -- identity user who registered as RESTAURANT_OWNER
    status              VARCHAR(20)   NOT NULL,          -- DRAFT | PENDING_APPROVAL | APPROVED | REJECTED | SUSPENDED
    name                VARCHAR(150)  NULL,
    description         VARCHAR(1000) NULL,
    fssai_license       VARCHAR(14)   NULL,
    gstin               VARCHAR(15)   NULL,
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
    CONSTRAINT pk_restaurant PRIMARY KEY (id),
    CONSTRAINT uk_restaurant_owner UNIQUE (owner_user_id),
    INDEX idx_restaurant_status_submitted (status, submitted_at),
    INDEX idx_restaurant_updated (updated_at)
) ENGINE = InnoDB;

-- Cuisine tags in the owner's order (sort_order 0 = primary cuisine). Uppercase tokens, at most 8.
CREATE TABLE restaurant_cuisine (
    restaurant_id  BINARY(16)   NOT NULL,
    sort_order     INT          NOT NULL,
    cuisine        VARCHAR(30)  NOT NULL,              -- e.g. NORTH_INDIAN
    CONSTRAINT pk_restaurant_cuisine PRIMARY KEY (restaurant_id, sort_order),
    CONSTRAINT fk_restaurant_cuisine_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE CASCADE,
    INDEX idx_restaurant_cuisine_cuisine (cuisine)
) ENGINE = InnoDB;

-- Weekly opening hours, at most one row per day. closes_at < opens_at = closes after midnight (overnight).
CREATE TABLE restaurant_opening_hours (
    restaurant_id  BINARY(16)   NOT NULL,
    day_of_week    VARCHAR(9)   NOT NULL,              -- MONDAY … SUNDAY
    opens_at       TIME         NOT NULL,
    closes_at      TIME         NOT NULL,
    CONSTRAINT pk_restaurant_opening_hours PRIMARY KEY (restaurant_id, day_of_week),
    CONSTRAINT fk_restaurant_opening_hours_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Who changed a restaurant's status, when and why (admin decisions are auditable).
CREATE TABLE restaurant_status_change (
    id             BINARY(16)   NOT NULL,
    restaurant_id  BINARY(16)   NOT NULL,
    from_status    VARCHAR(20)  NULL,
    to_status      VARCHAR(20)  NOT NULL,
    reason         VARCHAR(500) NULL,
    changed_by     BINARY(16)   NULL,
    changed_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_restaurant_status_change PRIMARY KEY (id),
    CONSTRAINT fk_restaurant_status_change_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE CASCADE,
    INDEX idx_restaurant_status_change_restaurant (restaurant_id, changed_at)
) ENGINE = InnoDB;
