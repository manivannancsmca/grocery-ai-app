package com.grocery.ai.tool;

import com.grocery.ai.approval.ApprovalAction;

import java.util.Set;

/**
 * One backend tool (contract §3): its name and description for the model, the label shown in the UI, its risk class,
 * the roles that may propose it, its parameters and what it does.
 *
 * @param proposerRoles roles that may call (READ / LOW_RISK_WRITE) or propose (SENSITIVE) it
 * @param executor      runs READ and LOW_RISK_WRITE tools (null for SENSITIVE)
 * @param action        the approval request a SENSITIVE tool proposes (null otherwise)
 */
public record ToolSpec(String name, String label, String description, RiskClass risk, Set<String> proposerRoles,
                       ArgSchema args, Executor executor, ApprovalAction action) {

    /** Runs a READ / LOW_RISK_WRITE tool through the gateway. */
    @FunctionalInterface
    public interface Executor {
        ToolResult execute(ToolCallContext call);
    }

    public static ToolSpec run(String name, String label, String description, RiskClass risk,
                               Set<String> proposerRoles, ArgSchema args, Executor executor) {
        return new ToolSpec(name, label, description, risk, proposerRoles, args, executor, null);
    }

    public static ToolSpec propose(String name, String label, String description, Set<String> proposerRoles,
                                   ArgSchema args, ApprovalAction action) {
        return new ToolSpec(name, label, description, RiskClass.SENSITIVE, proposerRoles, args, null, action);
    }

    public boolean mayBeUsedBy(Set<String> roles) {
        return roles.stream().anyMatch(proposerRoles::contains);
    }
}
