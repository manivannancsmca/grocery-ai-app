package com.grocery.ai.tool;

import com.grocery.ai.agent.AgentDefinition;
import com.grocery.ai.chat.ChatSink;
import com.grocery.ai.gateway.CallContext;
import com.grocery.common.security.CurrentUser;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything ToolGuard needs about one user message being answered: who asks (and their token), through which agent,
 * in which conversation, where the stream events go, and whether the tool call limit was hit.
 *
 * @param conversationId null for server-side tool use (insights)
 * @param messageId      the assistant message the calls belong to (null for insights)
 * @param toolResults    compact results of the successful calls of this turn ("tool → json"); they go into the
 *                       model's memory with the answer, so a follow-up question can use the ids they contain
 * @param toolResponses  everything the model got back from tools in this turn (results, refusals, errors), for the
 *                       grounding check of the answer; never stored
 * @param changed        a tool that changes something (a cart change, or a sensitive tool's approval request) ran in
 *                       this turn: an answer saying "added" or "prepared" is backed by it ({@code SkippedTools})
 */
public record ToolTurn(CurrentUser user, CallContext http, AgentDefinition agent, UUID conversationId,
                       UUID messageId, ChatSink sink, AtomicBoolean toolLimitExceeded,
                       List<String> toolResults, List<String> toolResponses, AtomicBoolean changed) {

    /** Per result: enough for ids, names and prices of a short list, not whole API answers. */
    static final int RESULT_LIMIT = 900;

    public static ToolTurn of(CurrentUser user, CallContext http, AgentDefinition agent, UUID conversationId,
                              UUID messageId, ChatSink sink) {
        return new ToolTurn(user, http, agent, conversationId, messageId, sink, new AtomicBoolean(),
                new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new AtomicBoolean());
    }

    /** Records what a successful READ / LOW_RISK_WRITE call returned (trimmed). */
    public void rememberResult(String tool, String resultJson) {
        String text = resultJson == null ? "" : resultJson;
        toolResults.add(tool + " → " + (text.length() > RESULT_LIMIT ? text.substring(0, RESULT_LIMIT) + "…" : text));
    }
}
