package com.grocery.ai.approval;

/**
 * {@code PENDING_APPROVAL → APPROVED → EXECUTING → COMPLETED | FAILED}; from {@code PENDING_APPROVAL} also
 * {@code REJECTED}, {@code EXPIRED}, {@code CANCELLED} (contract §4). APPROVED is passed through in the same
 * transaction that starts the execution.
 */
public enum ApprovalStatus {
    PENDING_APPROVAL, APPROVED, EXECUTING, COMPLETED, FAILED, REJECTED, EXPIRED, CANCELLED
}
