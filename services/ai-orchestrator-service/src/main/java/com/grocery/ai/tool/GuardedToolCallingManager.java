package com.grocery.ai.tool;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Objects;

/**
 * Spring AI's {@code DefaultToolCallingManager} for one turn (total limit = {@code grocery.ai.max-tool-calls}, THROW
 * on the next call, unknown names resolved to guarded callbacks), plus: the call that breaks the limit is audited as
 * BLOCKED AI_TOOL_LIMIT and the turn is marked, so the chat ends with {@code error AI_TOOL_LIMIT}.
 */
final class GuardedToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final ToolTurn turn;
    private final ToolGuard guard;

    GuardedToolCallingManager(ToolCallingManager delegate, ToolTurn turn, ToolGuard guard) {
        this.delegate = delegate;
        this.turn = turn;
        this.guard = guard;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        try {
            return delegate.executeToolCalls(prompt, chatResponse);
        } catch (ToolCallLimitExceededException e) {
            turn.toolLimitExceeded().set(true);
            List<Message> history = e.getPartialToolExecutionResult().conversationHistory();
            String name = "unknown";
            String arguments = null;
            if (!history.isEmpty() && history.getLast() instanceof ToolResponseMessage responses
                    && !responses.getResponses().isEmpty()) {
                ToolResponseMessage.ToolResponse breach = responses.getResponses().getLast();
                name = breach.name();
                if (history.size() > 1 && history.get(history.size() - 2) instanceof AssistantMessage calls) {
                    arguments = calls.getToolCalls().stream()
                            .filter(call -> Objects.equals(call.id(), breach.id()))
                            .map(AssistantMessage.ToolCall::arguments).findFirst().orElse(null);
                }
            }
            guard.limitExceeded(turn, name, arguments);
            throw e;
        }
    }
}
