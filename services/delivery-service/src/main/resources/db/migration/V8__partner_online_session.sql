-- =====================================================================================
-- V8: when delivery partners are online (the partner dashboard's "online time").
-- One row per online session: opened when the partner goes online, closed when they go offline. last_seen_at follows
-- their location reports (at most every 30 s), so a phone that stopped reporting (app closed while still "online")
-- does not count as online time: the session ends at its last report.
-- History starts with this migration; nothing is back-filled.
-- =====================================================================================
CREATE TABLE partner_online_session (
    id            BINARY(16)   NOT NULL,
    partner_id    BINARY(16)   NOT NULL,
    started_at    DATETIME(6)  NOT NULL,
    last_seen_at  DATETIME(6)  NOT NULL,   -- the latest sign of life: going online, or a location report
    ended_at      DATETIME(6)  NULL,       -- going offline (or the last report before a long silence); NULL = open
    CONSTRAINT pk_partner_online_session PRIMARY KEY (id),
    INDEX idx_online_session_partner (partner_id, started_at)
) ENGINE = InnoDB;
