-- Platform tables every event-publishing service owns (see libs/grocery-common).

-- Transactional outbox: written in the SAME transaction as the business change.
-- Debezium reads new rows from the binlog and publishes them to Kafka (topic = `topic` column,
-- key = aggregate_id, other columns become headers). `payload` is the JSON payload (audit, debugging, rollback path);
-- `payload_avro` the same payload in the Confluent wire format (ADR-0011), which Debezium publishes byte for byte.
-- Rows are deleted after 7 days by grocery-common's housekeeping.
CREATE TABLE outbox_event (
    id              CHAR(36)     NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(150) NOT NULL,
    event_version   INT          NOT NULL DEFAULT 1,
    payload         JSON         NOT NULL,
    payload_avro    LONGBLOB     NULL,
    correlation_id  VARCHAR(64)  NULL,
    causation_id    VARCHAR(64)  NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    INDEX idx_outbox_event_occurred_at (occurred_at)
) ENGINE = InnoDB;

-- Idempotent consumers: one row per (event, consumer) already handled (deleted after 35 days by housekeeping).
CREATE TABLE processed_event (
    event_id      CHAR(36)     NOT NULL,
    consumer      VARCHAR(100) NOT NULL,
    processed_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer)
) ENGINE = InnoDB;
