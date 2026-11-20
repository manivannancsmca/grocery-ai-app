-- Phase 12 (docs/api/phase-12-post-mvp-features.md §3): refunds of approved returns (order.events ReturnApproved).
-- reason RETURN fits refund.reason VARCHAR(20).
ALTER TABLE refund
    -- the return this refund is for (reason RETURN): at most one refund per return, whatever the event redelivery
    ADD COLUMN return_id         BINARY(16)     NULL AFTER requested_by,
    -- what ReturnApproved asked for; amount is that, capped at what was still refundable (never more than captured)
    ADD COLUMN requested_amount  DECIMAL(12,2)  NULL AFTER return_id,
    ADD CONSTRAINT uk_refund_return UNIQUE (return_id);
