-- Phase 12 §5 (docs/api/phase-12-post-mvp-features.md): the email channel.

-- Projection: who can be emailed. From identity.events UserRegistered (email, full name, role), kept up to date by
-- UserSuspended / UserReactivated (active), and from the validated access token of every user who opens the stream or
-- calls the preferences API (identity publishes no event for admins created by POST /api/admin/users or for the
-- bootstrap admin, so that is how admins and their roles become known).
CREATE TABLE email_address (
    user_id     BINARY(16)    NOT NULL,
    email       VARCHAR(254)  NOT NULL,
    full_name   VARCHAR(150)  NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,   -- false while the user account is suspended
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT pk_email_address PRIMARY KEY (user_id)
) ENGINE = InnoDB;

-- The user's roles: role audiences (role:FINANCE_ADMIN ...) are emailed to every active user holding the role.
CREATE TABLE email_address_role (
    user_id  BINARY(16)   NOT NULL,
    role     VARCHAR(60)  NOT NULL,
    CONSTRAINT pk_email_address_role PRIMARY KEY (user_id, role),
    INDEX idx_email_address_role_role (role, user_id),
    CONSTRAINT fk_email_address_role_user FOREIGN KEY (user_id) REFERENCES email_address (user_id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- GET/PUT /api/notifications/preferences. No row = every category on.
CREATE TABLE email_preference (
    user_id     BINARY(16)   NOT NULL,
    orders      BOOLEAN      NOT NULL,   -- orders, refunds and returns
    account     BOOLEAN      NOT NULL,   -- ACCOUNT_*
    approvals   BOOLEAN      NOT NULL,   -- AI_APPROVAL_REQUESTED
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_email_preference PRIMARY KEY (user_id)
) ENGINE = InnoDB;

-- One email per (notification, recipient), written in the SAME transaction as the notification (so it exists exactly
-- when the notification does), sent by the sender job (FOR UPDATE SKIP LOCKED, safe with replicas): retried after
-- 1 min, 5 min and 30 min, then FAILED. The rows go with their notification (retention, 90 days).
CREATE TABLE email_outbox (
    id               BINARY(16)     NOT NULL,   -- UUIDv7
    notification_id  BINARY(16)     NOT NULL,
    user_id          BINARY(16)     NOT NULL,
    to_address       VARCHAR(254)   NOT NULL,   -- the address when the notification was created
    to_name          VARCHAR(150)   NULL,
    type             VARCHAR(40)    NOT NULL,   -- the notification type
    subject          VARCHAR(200)   NOT NULL,   -- = the notification title
    body             VARCHAR(1000)  NOT NULL,   -- = the notification body
    link             VARCHAR(300)   NULL,       -- the app route; made absolute with APP_BASE_URL when sent
    status           VARCHAR(10)    NOT NULL,   -- PENDING | SENT | FAILED
    attempts         INT            NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(6)    NOT NULL,   -- due time; a claimed row is pushed forward by the lease
    last_error       VARCHAR(500)   NULL,
    created_at       DATETIME(6)    NOT NULL,
    sent_at          DATETIME(6)    NULL,
    CONSTRAINT pk_email_outbox PRIMARY KEY (id),
    CONSTRAINT uk_email_outbox_notification_user UNIQUE (notification_id, user_id),
    INDEX idx_email_outbox_due (status, next_attempt_at),
    INDEX idx_email_outbox_created (created_at),
    CONSTRAINT fk_email_outbox_notification FOREIGN KEY (notification_id)
        REFERENCES notification (id) ON DELETE CASCADE
) ENGINE = InnoDB;
