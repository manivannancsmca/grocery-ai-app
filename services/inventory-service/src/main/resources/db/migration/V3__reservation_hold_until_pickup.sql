-- Phase 6: stock reserved for orders (order.events → inventory.events).
--
-- A paid order (OrderConfirmed) keeps its stock until pickup: its reservation no longer expires, which is stored as
-- expires_at = NULL (held until it is committed in Phase 9 or released by a cancellation).
--
-- The expiry poller claims RESERVED reservations with expires_at < now. idx_stock_reservation_status_expires
-- (status, expires_at) still serves that range exactly: a NULL never satisfies "< now", and the range scan starts
-- after the NULL entries of the RESERVED prefix, so held reservations are never even read by the poller.
ALTER TABLE stock_reservation MODIFY expires_at DATETIME(6) NULL;
