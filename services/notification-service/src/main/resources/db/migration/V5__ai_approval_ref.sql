-- Phase 11 (docs/api/phase-11-agentic-ai-and-approvals.md §8): who decides an assistant's approval request. Only
-- ApprovalRequested names the approver (one user, or roles); its later events (ApprovalDecided, ApprovalExecuted,
-- ApprovalFailed) do not, so they look it up here to send AI_APPROVALS_CHANGED to the approvers too. All events of one
-- request share its key (approvalId), so they arrive in order on one partition: the row always exists by then.
-- Written for old events too (projections ignore the event-age rule). Rows older than grocery.notification.retention
-- are deleted by the nightly cleanup (a request is decided or expired within a day).
CREATE TABLE ai_approval_ref (
    approval_id       BINARY(16)     NOT NULL,
    approver_user_id  BINARY(16)     NULL,       -- the one user who decides (the customer, for their own order)
    approver_roles    VARCHAR(1000)  NOT NULL,   -- comma-separated role names when approver_user_id is NULL; '' else
    updated_at        DATETIME(6)    NOT NULL,
    CONSTRAINT pk_ai_approval_ref PRIMARY KEY (approval_id),
    INDEX idx_ai_approval_ref_updated (updated_at)
) ENGINE = InnoDB;
