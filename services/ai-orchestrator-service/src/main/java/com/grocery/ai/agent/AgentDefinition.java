package com.grocery.ai.agent;

import com.grocery.ai.tool.ToolSpec;
import com.grocery.common.security.CurrentUser;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One assistant (ADR-0008): its system prompt, its tool allowlist and the roles that may use it. The agent comes
 * from the URL and is checked against the caller's roles; the model never chooses one.
 */
public record AgentDefinition(String id, String name, String description, List<String> examplePrompts,
                              Set<String> roles, String systemPrompt, List<ToolSpec> tools) {

    public boolean allows(CurrentUser user) {
        return user.roles().stream().anyMatch(roles::contains);
    }

    /** ToolGuard step 1: only tools of this agent's allowlist exist for it. */
    public Optional<ToolSpec> tool(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }
}
