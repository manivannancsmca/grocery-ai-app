-- Phase 13 schema lint (tests/db-tests): the outbox table has grocery-common 0.9.0's shape in every service that owns
-- one. customer-service publishes no events yet (CustomerProfileUpdated is deferred, docs/events/event-catalog.md), so
-- its V1 copy of the platform tables never got the Phase 6 column (payload_avro, ADR-0011) nor the Phase 13 one
-- (traceparent). grocery-common's outbox writes payload_avro whenever Avro is enabled (the default), so the first event
-- this service publishes would fail without it.
-- Expand-only: two nullable columns without default (ALGORITHM=INSTANT on MySQL 8.4), nothing reads or writes them yet.
ALTER TABLE outbox_event ADD COLUMN payload_avro LONGBLOB NULL AFTER payload;
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55) NULL AFTER causation_id;
