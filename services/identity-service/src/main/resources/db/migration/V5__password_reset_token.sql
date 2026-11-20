-- Forgot / reset password (POST /api/auth/password/forgot and /reset): one row per emailed reset link.
-- Only the SHA-256 hash of the token is stored, like refresh_token: the raw token travels once, in the
-- PasswordResetRequested event, to notification-service, which emails the link. A link works once (used_at) and for
-- grocery.identity.password-reset.ttl (30 min). A newer request, a change of password and a reset end the unused links
-- of the account by moving their expires_at to that moment.
-- New refresh_token.revoke_reason values from this version on: PASSWORD_CHANGED, PASSWORD_RESET.
-- Expand-only: a new table.
CREATE TABLE password_reset_token (
    id            BINARY(16)   NOT NULL,
    user_id       BINARY(16)   NOT NULL,
    token_hash    CHAR(64)     NOT NULL,                 -- SHA-256 hex; the raw token is never stored
    created_at    DATETIME(6)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    used_at       DATETIME(6)  NULL,                     -- set when the link reset the password
    requested_ip  VARCHAR(64)  NULL,                     -- who asked (X-Forwarded-For from the gateway)
    CONSTRAINT pk_password_reset_token PRIMARY KEY (id),
    CONSTRAINT uk_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    INDEX idx_password_reset_token_user (user_id, created_at)   -- the per-account hourly limit
) ENGINE = InnoDB;
