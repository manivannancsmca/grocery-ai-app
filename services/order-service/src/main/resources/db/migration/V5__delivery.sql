-- Phase 9 (docs/api/phase-9-delivery.md §7): delivery.
-- New statuses OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED fit the existing VARCHAR(20) status columns
-- (customer_order and order_status_history); a failed delivery's reason (CUSTOMER_UNREACHABLE, ADDRESS_NOT_FOUND,
-- CUSTOMER_REFUSED, OTHER) is the history entry's reason and fits its VARCHAR(30).
ALTER TABLE customer_order
    -- the store's pickup location, snapshotted from the checkout preview at creation (null when unknown);
    -- sent as OrderConfirmed.storeLocation when both coordinates are known
    ADD COLUMN store_city               VARCHAR(100) NULL AFTER store_name,
    ADD COLUMN store_latitude           DOUBLE       NULL AFTER store_city,
    ADD COLUMN store_longitude          DOUBLE       NULL AFTER store_latitude,
    -- the delivery, from delivery.events: the assigned partner (DeliveryPartnerAssigned; cleared by
    -- DeliveryPartnerUnassigned), pickup, delivery or failure
    ADD COLUMN partner_id               BINARY(16)   NULL AFTER ready_at,
    ADD COLUMN partner_name             VARCHAR(150) NULL AFTER partner_id,
    ADD COLUMN partner_assigned_at      DATETIME(6)  NULL AFTER partner_name,
    ADD COLUMN picked_up_at             DATETIME(6)  NULL AFTER partner_assigned_at,
    ADD COLUMN delivered_at             DATETIME(6)  NULL AFTER picked_up_at,
    ADD COLUMN delivery_failed_at       DATETIME(6)  NULL AFTER delivered_at,
    ADD COLUMN delivery_failure_reason  VARCHAR(30)  NULL AFTER delivery_failed_at,
    ADD COLUMN delivery_failure_note    VARCHAR(300) NULL AFTER delivery_failure_reason;
