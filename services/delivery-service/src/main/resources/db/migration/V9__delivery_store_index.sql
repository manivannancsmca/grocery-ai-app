-- The store's deliveries (docs/api/phase-9-delivery.md, store addendum): a vendor's or restaurant's deliveries still at
-- the store (status PENDING / SEARCHING / ASSIGNED), so the store order board shows who is collecting each order.
-- Expand-only. Online on MySQL 8.4 (ALGORITHM=INPLACE, LOCK=NONE): reads and writes go on while it builds.
CREATE INDEX idx_delivery_store_status ON delivery (store_id, status) ALGORITHM = INPLACE LOCK = NONE;
