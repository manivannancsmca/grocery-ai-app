-- The central audit trail (2026-11-20): one row per action a person or the system took anywhere on the platform,
-- built only from events (consumer group reporting-service.audit; README "Audit trail"). The event id is the row id,
-- so a redelivered event can never add a second row. Searched by GET /api/admin/audit, newest first.
CREATE TABLE audit_entry (
    id            BINARY(16)     NOT NULL,                -- the event's eventId
    occurred_at   DATETIME(6)    NOT NULL,                -- when it happened (UTC): the event's own time
    category      VARCHAR(20)    NOT NULL,                -- SECURITY, ACCOUNTS, STORES, PARTNERS, PRICES, STOCK, ORDERS,
                                                          -- DELIVERIES, PAYMENTS, REVIEWS, AI, SETTINGS
    action        VARCHAR(60)    NOT NULL,                -- e.g. LOGIN_FAILURE, VENDOR_APPROVED, SETTING_CHANGED
    source        VARCHAR(40)    NOT NULL,                -- the producing service, e.g. identity-service
    actor_id      BINARY(16)     NULL,                    -- who acted; NULL for the system or an unknown caller
    actor_role    VARCHAR(20)    NOT NULL,                -- ADMIN, STORE, PARTNER, CUSTOMER, USER, SYSTEM, ANONYMOUS
    actor_name    VARCHAR(255)   NULL,                    -- the name the event itself carries (settings changes)
    target_type   VARCHAR(20)    NULL,                    -- USER, VENDOR, RESTAURANT, PARTNER, ORDER, PRODUCT, REVIEW,
                                                          -- APPROVAL, SETTING
    target_id     VARCHAR(100)   NULL,
    target_label  VARCHAR(255)   NULL,                    -- order number, business name, e-mail, setting …
    summary       VARCHAR(500)   NOT NULL,
    detail        VARCHAR(1000)  NULL,                    -- reason, comment, old → new value …
    ip_address    VARCHAR(64)    NULL,                    -- sign-in and account events only
    user_agent    VARCHAR(255)   NULL,
    event_type    VARCHAR(60)    NOT NULL,
    created_at    DATETIME(6)    NOT NULL,
    CONSTRAINT pk_audit_entry PRIMARY KEY (id),
    INDEX idx_audit_entry_occurred (occurred_at, id),
    INDEX idx_audit_entry_actor (actor_id, occurred_at),
    INDEX idx_audit_entry_target (target_type, target_id, occurred_at),
    INDEX idx_audit_entry_category (category, occurred_at)
) ENGINE = InnoDB;

-- Who a user id is, as far as the events tell: the account's e-mail from identity-service's sign-in events, and the
-- admin's name from settings changes. The audit list shows it next to every action of that user (admins included).
CREATE TABLE audit_actor (
    user_id     BINARY(16)    NOT NULL,
    email       VARCHAR(254)  NULL,
    name        VARCHAR(255)  NULL,
    seen_at     DATETIME(6)   NOT NULL,                   -- the newest event that told it: an older one never wins
    CONSTRAINT pk_audit_actor PRIMARY KEY (user_id)
) ENGINE = InnoDB;
