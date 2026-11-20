-- ADR-0011: Avro event payloads. grocery-common 0.6.0's outbox writes every payload twice in the same transaction:
-- `payload` (JSON, kept for auditing, debugging and the rollback path) and `payload_avro` (the same payload as Avro in
-- the Confluent wire format, validated against the central schema), which Debezium publishes byte for byte.
-- NULL for rows written before this migration (and with grocery.events.avro.enabled=false).
ALTER TABLE outbox_event ADD COLUMN payload_avro LONGBLOB NULL AFTER payload;
