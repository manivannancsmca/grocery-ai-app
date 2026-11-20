-- Avro event payloads (ADR-0011, grocery-common 0.6.0): the outbox also stores each payload in the Confluent wire
-- format (magic byte 0, 4-byte Schema Registry id, Avro binary), which Debezium publishes byte for byte.
-- The JSON `payload` column stays (audit, debugging, rollback path). NULL for rows written before this version
-- or with grocery.events.avro.enabled=false.
ALTER TABLE outbox_event ADD COLUMN payload_avro LONGBLOB NULL AFTER payload;
