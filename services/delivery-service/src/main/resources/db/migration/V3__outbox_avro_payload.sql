-- ADR-0011: events travel as Avro (Confluent wire format, central schemas in the Schema Registry).
-- grocery-common 0.6.0 writes the same payload twice in the business transaction: `payload` (JSON, kept for
-- auditing, debugging and the rollback path) and `payload_avro` (what Debezium publishes). NULL for rows written
-- before this migration or with grocery.events.avro.enabled=false.
ALTER TABLE outbox_event ADD COLUMN payload_avro LONGBLOB NULL AFTER payload;
