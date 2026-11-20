-- Phase 10 (docs/api/phase-10-event-driven-hardening.md §4, §5): the stuck-order watchdog and the order event timeline.

-- One flag per order and status the watchdog found the order stuck in (first detection only: the primary key makes
-- a second detection — another replica, the next run — a no-op, so OrderStuckDetected is published once). The flag
-- resolves itself (resolved_at) as soon as the order leaves that status: in the transaction of the status change, and
-- by the watchdog's next run. No foreign key: written while the order row is locked, never without its order.
CREATE TABLE stuck_order (
    order_id     BINARY(16)   NOT NULL,
    status       VARCHAR(20)  NOT NULL,   -- the status it is stuck in: CREATED … OUT_FOR_DELIVERY
    reason       VARCHAR(30)  NOT NULL,   -- STOCK_CHECK_OVERDUE | PAYMENT_EXPIRY_OVERDUE | STORE_RESPONSE_OVERDUE |
                                          -- PREPARATION_OVERDUE | PICKUP_OVERDUE | DELIVERY_OVERDUE
    since        DATETIME(6)  NOT NULL,   -- when the order entered that status
    due_at       DATETIME(6)  NOT NULL,   -- when it should have left it (the threshold); minutesOverdue counts from here
    detected_at  DATETIME(6)  NOT NULL,
    resolved_at  DATETIME(6)  NULL,       -- the order left the status
    CONSTRAINT pk_stuck_order PRIMARY KEY (order_id, status),
    INDEX idx_stuck_order_open (resolved_at, due_at)
) ENGINE = InnoDB;

-- The order's event timeline: every event order-service PUBLISHED for an order (outbox, same transaction) and every
-- event it CONSUMED for an order (inventory, payment, vendor / restaurant store events, delivery) with what it did:
-- APPLIED, IGNORED (a state guard rejected it; the guard's reason in detail) or DUPLICATE (already processed).
-- Written in the same transaction as the processing. Append-only (the outcome of a CONSUMED row is set within its own
-- transaction). No foreign key: the creating transaction writes OrderCreated before the order row is flushed.
CREATE TABLE order_event_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    order_id        BINARY(16)    NOT NULL,
    event_id        VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(150)  NOT NULL,
    source_topic    VARCHAR(100)  NOT NULL,
    direction       VARCHAR(10)   NOT NULL,   -- PUBLISHED | CONSUMED
    outcome         VARCHAR(10)   NOT NULL,   -- APPLIED | IGNORED | DUPLICATE
    detail          VARCHAR(500)  NULL,
    occurred_at     DATETIME(6)   NULL,       -- the event's own time (header occurredAt of a consumed event)
    recorded_at     DATETIME(6)   NOT NULL,
    correlation_id  VARCHAR(64)   NULL,
    CONSTRAINT pk_order_event_log PRIMARY KEY (id),
    INDEX idx_order_event_log_order (order_id, recorded_at, id)
) ENGINE = InnoDB;
