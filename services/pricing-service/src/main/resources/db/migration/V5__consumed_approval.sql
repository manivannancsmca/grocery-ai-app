-- Phase 12 §6: pricing-service becomes an approval-token target (the vendor assistant's CHANGE_PRICE calls
-- PUT /api/vendor/prices/{productId} with an X-Approval-Token). Single use of AI approval tokens (grocery-common 0.8.0
-- approval filter, Phase 11 contract §5): the filter inserts the approval id before the handler runs; a second use
-- answers 409 APPROVAL_ALREADY_USED.
CREATE TABLE consumed_approval (
  approval_id BINARY(16)   NOT NULL,
  action      VARCHAR(40)  NOT NULL,
  approver_id BINARY(16)   NOT NULL,
  consumed_at DATETIME(6)  NOT NULL,
  CONSTRAINT pk_consumed_approval PRIMARY KEY (approval_id),
  INDEX idx_consumed_approval_consumed_at (consumed_at)
) ENGINE = InnoDB;
