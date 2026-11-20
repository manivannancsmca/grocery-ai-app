-- Identity: accounts, roles, refresh-token sessions, business memberships (claims), signing keys, audit.
-- IDs are application-generated UUIDv7 stored as BINARY(16) (use BIN_TO_UUID(id) when querying by hand).

CREATE TABLE user_account (
    id                  BINARY(16)    NOT NULL,
    email               VARCHAR(254)  NOT NULL,
    phone               VARCHAR(20)   NULL,
    password_hash       VARCHAR(100)  NOT NULL,
    full_name           VARCHAR(120)  NOT NULL,
    status              VARCHAR(20)   NOT NULL,           -- ACTIVE | SUSPENDED
    suspension_reason   VARCHAR(500)  NULL,
    failed_login_count  INT           NOT NULL DEFAULT 0,
    locked_until        DATETIME(6)   NULL,
    last_login_at       DATETIME(6)   NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_account PRIMARY KEY (id),
    CONSTRAINT uk_user_account_email UNIQUE (email),
    INDEX idx_user_account_status (status),
    INDEX idx_user_account_created_at (created_at)
) ENGINE = InnoDB;

CREATE TABLE user_role (
    user_id  BINARY(16)  NOT NULL,
    role     VARCHAR(30) NOT NULL,
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    INDEX idx_user_role_role (role)
) ENGINE = InnoDB;

-- One row per issued refresh token. A "family" is one login session; rotation keeps the family,
-- reuse of a rotated token (after the grace window) revokes the whole family.
CREATE TABLE refresh_token (
    id             BINARY(16)   NOT NULL,
    user_id        BINARY(16)   NOT NULL,
    family_id      BINARY(16)   NOT NULL,
    token_hash     CHAR(64)     NOT NULL,                 -- SHA-256 hex; the raw token is never stored
    issued_at      DATETIME(6)  NOT NULL,
    expires_at     DATETIME(6)  NOT NULL,
    revoked_at     DATETIME(6)  NULL,
    revoke_reason  VARCHAR(30)  NULL,                     -- ROTATED | LOGOUT | REUSE_DETECTED | USER_SUSPENDED
    replaced_by_id BINARY(16)   NULL,
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    INDEX idx_refresh_token_family (family_id),
    INDEX idx_refresh_token_user (user_id),
    INDEX idx_refresh_token_expires (expires_at)
) ENGINE = InnoDB;

-- Which business (vendor / restaurant) a user owns, mirrored from vendor/restaurant events.
-- Only ACTIVE memberships become JWT claims (vendor_id / restaurant_id).
CREATE TABLE business_membership (
    id             BINARY(16)   NOT NULL,
    user_id        BINARY(16)   NOT NULL,
    business_type  VARCHAR(20)  NOT NULL,                 -- VENDOR | RESTAURANT
    business_id    BINARY(16)   NOT NULL,
    status         VARCHAR(20)  NOT NULL,                 -- PENDING | ACTIVE | REJECTED | SUSPENDED
    updated_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_business_membership PRIMARY KEY (id),
    CONSTRAINT uk_business_membership_business UNIQUE (business_type, business_id),
    INDEX idx_business_membership_user (user_id)
) ENGINE = InnoDB;

-- RS256 signing keys (local/MVP). Production moves the private key to a KMS/Secrets Manager.
CREATE TABLE jwt_signing_key (
    kid                 VARCHAR(64)  NOT NULL,
    public_key_base64   TEXT         NOT NULL,            -- X.509 SubjectPublicKeyInfo
    private_key_base64  TEXT         NOT NULL,            -- PKCS#8
    active              BOOLEAN      NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_jwt_signing_key PRIMARY KEY (kid)
) ENGINE = InnoDB;

CREATE TABLE auth_audit (
    id           BINARY(16)    NOT NULL,
    user_id      BINARY(16)    NULL,
    email        VARCHAR(254)  NULL,
    event_type   VARCHAR(40)   NOT NULL,
    detail       VARCHAR(500)  NULL,
    ip_address   VARCHAR(64)   NULL,
    user_agent   VARCHAR(255)  NULL,
    occurred_at  DATETIME(6)   NOT NULL,
    CONSTRAINT pk_auth_audit PRIMARY KEY (id),
    INDEX idx_auth_audit_user (user_id, occurred_at),
    INDEX idx_auth_audit_occurred (occurred_at)
) ENGINE = InnoDB;
