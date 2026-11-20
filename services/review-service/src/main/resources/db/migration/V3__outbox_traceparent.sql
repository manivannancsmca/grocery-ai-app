-- Phase 13 (grocery-common 0.9.0): the W3C trace context of the transaction that wrote the event
-- (00-<trace id>-<span id>-<flags>, always 55 chars). Debezium's EventRouter places it in the Kafka header
-- `traceparent`, so consumers continue the same trace. NULL when no span was active (consumers start a new trace).
-- Expand-only: nullable, no default (ALGORITHM=INSTANT on MySQL 8.4).
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55) NULL AFTER causation_id;
