-- Phase 10 (docs/api/phase-10-event-driven-hardening.md §6): location_sample rows older than
-- grocery.delivery.location-sample-retention (30 days) are deleted on a schedule, in batches of 1000, oldest first.
-- The index lets each batch find its rows without scanning the table.
CREATE INDEX idx_location_sample_recorded ON location_sample (recorded_at);
