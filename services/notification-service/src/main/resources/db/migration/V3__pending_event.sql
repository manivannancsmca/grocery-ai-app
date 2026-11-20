-- Payment events that arrived before their order was known (order.events and payment.events are different topics:
-- no ordering guarantee). Parked in the same transaction as their processed_event mark instead of retrying the
-- record, so the partition is never blocked; applied (and deleted) in the transaction that makes the order known,
-- in arrival order (seq), with their ORIGINAL occurredAt for the max-event-age rule. The nightly cleanup drops rows
-- older than grocery.notification.pending-event-max-age (24 h) with a WARN.
CREATE TABLE pending_event (
    seq            BIGINT        NOT NULL AUTO_INCREMENT,
    event_id       CHAR(36)      NOT NULL,
    order_id       BINARY(16)    NOT NULL,
    topic          VARCHAR(100)  NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,
    event_version  INT           NOT NULL DEFAULT 1,
    occurred_at    DATETIME(6)   NULL,       -- the event's own occurredAt header; NULL = unknown (treated as new)
    payload        MEDIUMTEXT    NOT NULL,   -- the JSON exactly as received
    received_at    DATETIME(6)   NOT NULL,
    CONSTRAINT pk_pending_event PRIMARY KEY (seq),
    CONSTRAINT uk_pending_event_event UNIQUE (event_id),
    INDEX idx_pending_event_order (order_id, seq),
    INDEX idx_pending_event_received (received_at)
) ENGINE = InnoDB;
