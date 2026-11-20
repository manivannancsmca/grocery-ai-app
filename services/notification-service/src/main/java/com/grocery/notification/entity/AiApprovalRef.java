package com.grocery.notification.entity;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11: a row of the {@code ai_approval_ref} projection — who decides an assistant's approval request: the one
 * user {@code approverUserId} (the customer, for their own order), or else anybody holding one of
 * {@code approverRoles} (role names, empty when the user is set).
 */
public record AiApprovalRef(UUID approvalId, UUID approverUserId, List<String> approverRoles) {

    public AiApprovalRef {
        approverRoles = approverRoles == null ? List.of() : List.copyOf(approverRoles);
    }
}
