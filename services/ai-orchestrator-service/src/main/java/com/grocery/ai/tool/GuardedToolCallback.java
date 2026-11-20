package com.grocery.ai.tool;

import io.micrometer.tracing.Span;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The only kind of {@link ToolCallback} the model can reach: every call — to an allow-listed tool, to another agent's
 * tool, or to a name the model invented (via {@link ToolGuard#resolver}) — goes through {@link ToolGuard}, which
 * checks, audits, streams the {@code tool} events and answers the model with JSON. It never throws to Spring AI.
 */
final class GuardedToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final ToolTurn turn;
    private final ToolGuard guard;
    /** The span of the turn that created this callback (null without tracing): the call runs in it. */
    private final Span turnSpan;

    GuardedToolCallback(ToolDefinition definition, ToolTurn turn, ToolGuard guard, Span turnSpan) {
        this.definition = definition;
        this.turn = turn;
        this.guard = guard;
        this.turnSpan = turnSpan;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return guard.traced(turn, definition.name(), toolInput, turnSpan);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }
}
