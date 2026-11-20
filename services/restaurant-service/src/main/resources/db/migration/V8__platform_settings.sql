-- =====================================================================================
-- V8: admin platform settings (docs/api/platform-settings.md), area "restaurants".
-- platform_setting: an admin's override of one business setting, key "<area>.<field>" (e.g. restaurants.acceptanceTimeMinutes).
-- No row, or a NULL value (the admin reset it), = the default from application.yml / env, so nothing changes until
-- an admin saves. version counts the changes of the key; the area's version (the sum of its keys') is the optimistic
-- lock of the admin API.
-- platform_setting_change: the audit trail, one row per changed key (old / new NULL = the default).
-- Expand-only: two new tables.
-- =====================================================================================
CREATE TABLE platform_setting (
    setting_key      VARCHAR(100) NOT NULL,
    setting_value    VARCHAR(500) NULL,       -- NULL = reset to the default
    updated_by       BINARY(16)   NOT NULL,   -- the admin's user id
    updated_by_name  VARCHAR(255) NULL,       -- their name (or email) when they saved, for the admin page
    updated_at       DATETIME(6)  NOT NULL,
    version          BIGINT       NOT NULL,   -- 1 on insert, +1 per change
    CONSTRAINT pk_platform_setting PRIMARY KEY (setting_key)
) ENGINE = InnoDB;

CREATE TABLE platform_setting_change (
    id               BINARY(16)   NOT NULL,
    setting_key      VARCHAR(100) NOT NULL,
    old_value        VARCHAR(500) NULL,
    new_value        VARCHAR(500) NULL,
    changed_by       BINARY(16)   NOT NULL,
    changed_by_name  VARCHAR(255) NULL,
    changed_at       DATETIME(6)  NOT NULL,
    CONSTRAINT pk_platform_setting_change PRIMARY KEY (id),
    INDEX idx_platform_setting_change_key (setting_key, changed_at)
) ENGINE = InnoDB;
