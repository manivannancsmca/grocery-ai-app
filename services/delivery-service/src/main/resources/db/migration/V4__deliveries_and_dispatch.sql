-- Phase 9: deliveries, offers and assignment, partner availability and live location (docs/api/phase-9-delivery.md).
-- IDs BINARY(16) (UUIDv7; order / customer / store ids come from order-service), money DECIMAL(12,2) INR, timestamps
-- DATETIME(6) UTC, coordinates DOUBLE (WGS84 degrees), distances straight-line km.
--
-- Locking rule (dispatch/service): every change takes the delivery row lock first (SELECT … FOR UPDATE; the poller
-- claims with FOR UPDATE SKIP LOCKED), then partner rows (ascending id). An offer row changes only under the lock of
-- its delivery. Nothing ever locks a partner first and a delivery afterwards.

-- Partner availability (the partner's own switch), the delivery they are on, and their last stored location.
-- Updated without touching updated_at (that is the profile's "last changed", used by the admin review list).
ALTER TABLE delivery_partner
    ADD COLUMN online                BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN active_delivery_id    BINARY(16)   NULL,       -- the ASSIGNED / PICKED_UP delivery, NULL when free
    ADD COLUMN last_latitude         DOUBLE       NULL,
    ADD COLUMN last_longitude        DOUBLE       NULL,
    ADD COLUMN last_heading          DOUBLE       NULL,       -- degrees from north, NULL if unknown
    ADD COLUMN last_speed_kmh        DOUBLE       NULL,
    ADD COLUMN last_accuracy_meters  DOUBLE       NULL,
    ADD COLUMN last_location_at      DATETIME(6)  NULL,       -- when the last report was STORED (freshness, 2 s throttle)
    ADD INDEX idx_delivery_partner_online (online, status);  -- the admin's online partner list

-- One delivery per order: a SNAPSHOT of what dispatch needs, taken from OrderConfirmed (store pickup point, drop
-- address, recipient, note, item count, total), then the delivery status, the search state and the outcome.
CREATE TABLE delivery (
    id                    BINARY(16)     NOT NULL,
    order_id              BINARY(16)     NOT NULL,
    order_number          VARCHAR(20)    NOT NULL,
    order_type            VARCHAR(20)    NOT NULL,   -- GROCERY | RESTAURANT
    customer_id           BINARY(16)     NOT NULL,   -- the order's customer (tracking ownership)
    store_id              BINARY(16)     NOT NULL,   -- vendorId or restaurantId
    store_name            VARCHAR(150)   NULL,       -- OrderConfirmed.storeLocation; all four NULL in older events
    store_city            VARCHAR(100)   NULL,
    store_latitude        DOUBLE         NULL,
    store_longitude       DOUBLE         NULL,
    drop_recipient_name   VARCHAR(150)   NULL,       -- OrderConfirmed.deliveryAddress
    drop_phone            VARCHAR(20)    NULL,
    drop_line1            VARCHAR(200)   NULL,
    drop_line2            VARCHAR(200)   NULL,
    drop_landmark         VARCHAR(200)   NULL,
    drop_city             VARCHAR(100)   NULL,
    drop_postal_code      VARCHAR(20)    NULL,
    drop_latitude         DOUBLE         NULL,
    drop_longitude        DOUBLE         NULL,
    note                  VARCHAR(300)   NULL,       -- the customer's delivery note
    item_count            INT            NOT NULL,   -- units ordered (sum of quantities)
    total                 DECIMAL(12,2)  NOT NULL,   -- what the customer paid
    currency              VARCHAR(3)     NOT NULL,
    status                VARCHAR(20)    NOT NULL,   -- PENDING | SEARCHING | ASSIGNED | PICKED_UP | DELIVERED | FAILED | CANCELLED
    ready_for_pickup      BOOLEAN        NOT NULL DEFAULT FALSE,   -- OrderReadyForPickup arrived
    ready_by              DATETIME(6)    NULL,       -- the store's estimate (OrderAccepted)
    ready_at              DATETIME(6)    NULL,
    partner_id            BINARY(16)     NULL,       -- the assigned partner (kept after DELIVERED / FAILED / CANCELLED)
    -- search state (status SEARCHING)
    search_round          INT            NOT NULL DEFAULT 0,   -- 1 = first search; +1 per retry / admin re-search
    search_radius_index   INT            NOT NULL DEFAULT 0,   -- index into search-radii-km; = number of radii: round exhausted
    search_wave           INT            NOT NULL DEFAULT 0,   -- waves sent so far (all rounds)
    offers_sent           INT            NOT NULL DEFAULT 0,   -- offers sent so far (all rounds)
    assignment_failed     BOOLEAN        NOT NULL DEFAULT FALSE,   -- nobody inside the largest radius; cleared on assignment
    assignment_failed_at  DATETIME(6)    NULL,       -- first time it failed (DeliveryAssignmentFailed is published once)
    next_search_at        DATETIME(6)    NULL,       -- when the poller looks at it next (offer expiry / next wave / retry)
    -- lifecycle
    confirmed_at          DATETIME(6)    NULL,       -- the order was paid
    created_at            DATETIME(6)    NOT NULL,
    searching_at          DATETIME(6)    NULL,       -- the store accepted (OrderAccepted)
    assigned_at           DATETIME(6)    NULL,
    picked_up_at          DATETIME(6)    NULL,
    delivered_at          DATETIME(6)    NULL,
    failed_at             DATETIME(6)    NULL,
    cancelled_at          DATETIME(6)    NULL,
    finished_at           DATETIME(6)    NULL,       -- delivered_at or failed_at (the partner's history)
    failure_reason        VARCHAR(30)    NULL,       -- CUSTOMER_UNREACHABLE | ADDRESS_NOT_FOUND | CUSTOMER_REFUSED | OTHER
    failure_note          VARCHAR(300)   NULL,
    updated_at            DATETIME(6)    NOT NULL,
    version               BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_delivery PRIMARY KEY (id),
    CONSTRAINT uk_delivery_order UNIQUE (order_id),
    CONSTRAINT ck_delivery_amounts CHECK (total >= 0 AND item_count >= 0),
    INDEX idx_delivery_search (status, next_search_at),               -- the dispatch poller: SEARCHING and due
    INDEX idx_delivery_created (created_at),                          -- the admin list, newest first
    INDEX idx_delivery_status_created (status, created_at),           -- …filtered by status
    INDEX idx_delivery_failed_created (assignment_failed, created_at),-- …filtered by assignmentFailed
    INDEX idx_delivery_partner_finished (partner_id, finished_at),    -- the partner's history / summary, admin by partner
    INDEX idx_delivery_order_number (order_number)                    -- the admin search (q)
) ENGINE = InnoDB;

-- Every status the delivery went through (the timeline), oldest first. Append-only.
CREATE TABLE delivery_status_history (
    delivery_id    BINARY(16)    NOT NULL,
    seq            INT           NOT NULL,   -- 0, 1, 2 … per delivery
    status         VARCHAR(20)   NOT NULL,
    partner_id     BINARY(16)    NULL,       -- the partner concerned (assigned, unassigned, …)
    reason         VARCHAR(300)  NULL,       -- failure / cancellation / reassignment reason
    actor_user_id  BINARY(16)    NULL,       -- the partner or admin; NULL for the system (events, pollers)
    changed_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_delivery_status_history PRIMARY KEY (delivery_id, seq),
    CONSTRAINT fk_delivery_status_history_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id)
) ENGINE = InnoDB;

-- Partners never offered this delivery again (an admin took it away from them: reassign without a partner).
CREATE TABLE delivery_excluded_partner (
    delivery_id  BINARY(16)  NOT NULL,
    partner_id   BINARY(16)  NOT NULL,
    CONSTRAINT pk_delivery_excluded_partner PRIMARY KEY (delivery_id, partner_id),
    CONSTRAINT fk_delivery_excluded_partner_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id)
) ENGINE = InnoDB;

-- An offer of a delivery to one partner. PENDING until accepted, rejected, expired or withdrawn; changed only under
-- the delivery row lock. At most one PENDING offer per partner at a time (checked under the partner row lock).
CREATE TABLE delivery_offer (
    id            BINARY(16)   NOT NULL,
    delivery_id   BINARY(16)   NOT NULL,
    partner_id    BINARY(16)   NOT NULL,
    search_round  INT          NOT NULL,   -- partners offered in the current round are not offered again in it
    wave          INT          NOT NULL,
    distance_km   DOUBLE       NOT NULL,   -- partner -> pickup point when offered
    status        VARCHAR(20)  NOT NULL,   -- PENDING | ACCEPTED | REJECTED | EXPIRED | WITHDRAWN
    sent_at       DATETIME(6)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    closed_at     DATETIME(6)  NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_delivery_offer PRIMARY KEY (id),
    CONSTRAINT fk_delivery_offer_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    INDEX idx_delivery_offer_delivery (delivery_id, status),                  -- a delivery's open offers
    INDEX idx_delivery_offer_round (delivery_id, search_round),              -- partners offered in this round
    INDEX idx_delivery_offer_partner (partner_id, status, expires_at)        -- a partner's pending offers
) ENGINE = InnoDB;

-- The sampled trail of active deliveries (ASSIGNED / PICKED_UP): at most one row per delivery per 15 s.
CREATE TABLE location_sample (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    delivery_id      BINARY(16)   NOT NULL,
    partner_id       BINARY(16)   NOT NULL,
    latitude         DOUBLE       NOT NULL,
    longitude        DOUBLE       NOT NULL,
    heading          DOUBLE       NULL,
    speed_kmh        DOUBLE       NULL,
    accuracy_meters  DOUBLE       NULL,
    recorded_at      DATETIME(6)  NOT NULL,
    CONSTRAINT pk_location_sample PRIMARY KEY (id),
    CONSTRAINT fk_location_sample_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    INDEX idx_location_sample_delivery (delivery_id, recorded_at)
) ENGINE = InnoDB;
