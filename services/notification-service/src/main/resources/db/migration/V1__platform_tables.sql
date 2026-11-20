-- Platform tables (see libs/grocery-common). notification-service publishes no events, so it has no outbox_event
-- table: only the idempotent-consumer marker.

-- Idempotent consumers: one row per (event, consumer) already handled. Written in the same transaction as the
-- notifications and projections of the event (consumer notification-service.dispatch).
CREATE TABLE processed_event (
    event_id      CHAR(36)     NOT NULL,
    consumer      VARCHAR(100) NOT NULL,
    processed_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer)
) ENGINE = InnoDB;
