-- Phase 7: the notification inbox, per-user read state and the projections recipients are resolved from.

-- One row per (event, distinct recipient text). A notification for several admin roles is ONE row with several
-- audience rows, so an admin holding two of those roles sees it once. id = UUIDv7 = the SSE event id (time-ordered).
CREATE TABLE notification (
    id               BINARY(16)    NOT NULL,
    type             VARCHAR(40)   NOT NULL,
    category         VARCHAR(20)   NOT NULL,
    severity         VARCHAR(10)   NOT NULL,
    title            VARCHAR(200)  NOT NULL,
    body             VARCHAR(1000) NOT NULL,
    link             VARCHAR(300)  NULL,
    data             JSON          NOT NULL,
    source_event_id  CHAR(36)      NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    CONSTRAINT pk_notification PRIMARY KEY (id),
    INDEX idx_notification_created_at (created_at),
    INDEX idx_notification_source_event (source_event_id)
) ENGINE = InnoDB;

-- Who a notification is addressed to: 'user:<uuid>' or 'role:<ROLE>' (never chosen by a client).
CREATE TABLE notification_audience (
    notification_id  BINARY(16)   NOT NULL,
    audience_key     VARCHAR(80)  NOT NULL,
    CONSTRAINT pk_notification_audience PRIMARY KEY (notification_id, audience_key),
    INDEX idx_notification_audience_key (audience_key, notification_id),
    CONSTRAINT fk_notification_audience_notification FOREIGN KEY (notification_id)
        REFERENCES notification (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Read state is per user, also for notifications addressed to a role.
CREATE TABLE notification_read (
    user_id          BINARY(16)   NOT NULL,
    notification_id  BINARY(16)   NOT NULL,
    read_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_notification_read PRIMARY KEY (user_id, notification_id),
    INDEX idx_notification_read_notification (notification_id),
    CONSTRAINT fk_notification_read_notification FOREIGN KEY (notification_id)
        REFERENCES notification (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Projection: vendorId / restaurantId -> owner (vendor.events, restaurant.events lifecycle + listing events).
CREATE TABLE store_owner (
    store_type     VARCHAR(12)   NOT NULL,   -- VENDOR | RESTAURANT
    store_id       BINARY(16)    NOT NULL,
    owner_user_id  BINARY(16)    NOT NULL,
    store_name     VARCHAR(150)  NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_store_owner PRIMARY KEY (store_type, store_id)
) ENGINE = InnoDB;

-- Projection: what payment events need about their order (they carry only the orderId). OrderCreated fills it,
-- every order event updates the status.
CREATE TABLE order_ref (
    order_id      BINARY(16)     NOT NULL,
    order_number  VARCHAR(20)    NOT NULL,
    order_type    VARCHAR(12)    NULL,      -- GROCERY | RESTAURANT
    customer_id   BINARY(16)     NOT NULL,
    store_id      BINARY(16)     NULL,      -- vendorId (GROCERY) / restaurantId (RESTAURANT)
    store_name    VARCHAR(150)   NULL,
    total         DECIMAL(12, 2) NULL,
    status        VARCHAR(20)    NOT NULL,
    updated_at    DATETIME(6)    NOT NULL,
    CONSTRAINT pk_order_ref PRIMARY KEY (order_id)
) ENGINE = InnoDB;
