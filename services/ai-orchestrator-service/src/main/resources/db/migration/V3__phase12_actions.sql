-- Phase 12 §6 (docs/api/phase-12-post-mvp-features.md): the vendor, restaurant and delivery assistants add the actions
-- REQUEST_RETURN, ADJUST_STOCK, CHANGE_PRICE, SEND_PURCHASE_ORDER, SET_MENU_ITEM_AVAILABILITY, ACCEPT_STORE_ORDER,
-- REJECT_STORE_ORDER and REASSIGN_DELIVERY (action stays VARCHAR(30); the longest is 26 characters).
--
-- The duplicate-proposal key is HEX(requested_by) (32) + ':' + action + ':' + body_sha256 (64): 120 characters only fit
-- actions of up to 22 characters, so SET_MENU_ITEM_AVAILABILITY overflowed it. Widened to 130 (actions up to 32).
ALTER TABLE approval_request
    MODIFY pending_key VARCHAR(130) GENERATED ALWAYS AS (
        IF(status = 'PENDING_APPROVAL', CONCAT(HEX(requested_by), ':', action, ':', body_sha256), NULL)) STORED;
