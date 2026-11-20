-- Phase 8 (docs/api/phase-8-vendor-and-restaurant-fulfillment.md §4): the store's decisions on the order.
-- New statuses ACCEPTED, PREPARING, READY_FOR_PICKUP fit the existing VARCHAR(20) status columns (customer_order and
-- order_status_history); new cancellation reasons STORE_REJECTED, STORE_NO_RESPONSE fit VARCHAR(30); the new actor
-- STORE fits cancelled_by VARCHAR(10).
ALTER TABLE customer_order
    ADD COLUMN accepted_at       DATETIME(6)  NULL AFTER confirmed_at,     -- the store accepted (its own time)
    ADD COLUMN ready_in_minutes  INT          NULL AFTER accepted_at,      -- the store's estimate
    ADD COLUMN ready_by          DATETIME(6)  NULL AFTER ready_in_minutes, -- accepted_at + ready_in_minutes
    ADD COLUMN preparing_at      DATETIME(6)  NULL AFTER ready_by,         -- preparing (restaurant) / packing (grocery)
    ADD COLUMN ready_at          DATETIME(6)  NULL AFTER preparing_at,     -- ready for pickup
    -- a store rejection is stored as "<reason>: <note>" (note ≤ 300 + the reason)
    MODIFY COLUMN cancellation_note VARCHAR(320) NULL;
