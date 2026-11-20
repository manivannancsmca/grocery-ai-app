-- Platform tables (see libs/grocery-common). reporting-service publishes no events, so it has no outbox_event table:
-- only the idempotent-consumer marker.

-- Idempotent consumers: one row per (event, consumer) already applied, written in the same transaction as the facts
-- of the event (consumer reporting-service.facts).
CREATE TABLE processed_event (
    event_id      CHAR(36)     NOT NULL,
    consumer      VARCHAR(100) NOT NULL,
    processed_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer)
) ENGINE = InnoDB;
