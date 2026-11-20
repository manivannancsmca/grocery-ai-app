-- Phase 9 (docs/api/phase-9-delivery.md §7): the order projection keeps the delivery's assigned partner, so
-- OrderReadyForPickup can tell them (PICKUP_READY). Set by DeliveryPartnerAssigned, cleared by
-- DeliveryPartnerUnassigned / DeliveryCancelled (only when it is still the recorded partner). The new order statuses
-- OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED fit the existing status VARCHAR(20).
ALTER TABLE order_ref
    ADD COLUMN delivery_id  BINARY(16)  NULL AFTER status,        -- the delivery of the assigned partner
    ADD COLUMN partner_id   BINARY(16)  NULL AFTER delivery_id;   -- the assigned delivery partner (= their user id)
