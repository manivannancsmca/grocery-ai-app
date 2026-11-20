-- Rebuild of the reporting read model, step 2 of README.md "Rebuild" (run with every reporting-service replica
-- STOPPED, as reporting_user on reporting_db). Empties every fact table and the idempotency markers; the schema and
-- Flyway's history stay. Then reset the consumer group to the earliest offsets and start the service again.
TRUNCATE TABLE order_fact;
TRUNCATE TABLE order_line_fact;
TRUNCATE TABLE refund_fact;
TRUNCATE TABLE return_fact;
TRUNCATE TABLE return_line_fact;
TRUNCATE TABLE delivery_fact;
TRUNCATE TABLE review_fact;
TRUNCATE TABLE rating_fact;
TRUNCATE TABLE receipt_fact;
TRUNCATE TABLE receipt_line_fact;
TRUNCATE TABLE store_dim;
TRUNCATE TABLE item_dim;
TRUNCATE TABLE partner_dim;
-- Only the facts consumer's markers: the audit trail (audit_entry, audit_actor, consumer reporting-service.audit) is
-- NOT part of the rebuild. It is the long-term record: Kafka keeps the events only days, the trail keeps them years.
DELETE FROM processed_event WHERE consumer = 'reporting-service.facts';
