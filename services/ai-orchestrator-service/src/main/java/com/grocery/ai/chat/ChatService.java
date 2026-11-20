package com.grocery.ai.chat;

import com.grocery.ai.agent.AgentDefinition;
import com.grocery.ai.agent.Agents;
import com.grocery.ai.audit.AiMetrics;
import com.grocery.ai.audit.AuditLog;
import com.grocery.ai.config.AiProperties;
import com.grocery.ai.conversation.ConversationService;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.model.ModelErrors;
import com.grocery.ai.model.ModelStatus;
import com.grocery.ai.tool.ShoppingCommands;
import com.grocery.ai.tool.ToolGuard;
import com.grocery.ai.tool.ToolTurn;
import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * {@code POST /api/ai/agents/{agent}/chat} (contract §2). Before the stream starts: the agent (404 / 403, audited),
 * the message (1–2000), the per-user rate limit (429) and the conversation (404). Then, on a virtual thread, one turn:
 * <ol>
 *   <li>{@code conversation} event;</li>
 *   <li>prompt = agent system prompt + server-built context line, the conversation's last messages from chat memory,
 *       the new message (at most {@code grocery.ai.memory-messages} in all);</li>
 *   <li>Spring AI {@code ChatClient} streaming with the agent's guarded tools and the turn's ToolCallingAdvisor; tokens
 *       are forwarded as they arrive, ToolGuard streams {@code tool} / {@code approval} events;</li>
 *   <li>an answer given without any tool call that claims a change, says it cannot see the data or states figures
 *       from nowhere ({@link SkippedTools}) is sent back once: {@code retry} event, then the new answer's events;</li>
 *   <li>{@code done} (answer stored, memory updated) or {@code error} (AI_UNAVAILABLE, AI_TIMEOUT, AI_TOOL_LIMIT,
 *       AI_ERROR). The user message is stored in every case.</li>
 * </ol>
 */
@Service
public class ChatService {

    public static final int MAX_MESSAGE_LENGTH = 2000;
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(15).toMillis();

    private final Agents agents;
    private final AuditLog audit;
    private final ConversationService conversations;
    private final ChatMemory chatMemory;
    private final ChatClient chatClient;
    private final ToolGuard guard;
    private final ContextBuilder contextBuilder;
    private final ModelStatus modelStatus;
    private final AiProperties properties;
    private final AsyncTaskExecutor executor;
    private final JsonMapper jsonMapper;
    private final AiMetrics metrics;
    private final Clock clock;
    private final ShoppingCommands shoppingCommands;

    public ChatService(Agents agents, AuditLog audit, ConversationService conversations, ChatMemory chatMemory,
                       ChatClient chatClient, ToolGuard guard, ContextBuilder contextBuilder, ModelStatus modelStatus,
                       AiProperties properties, AsyncTaskExecutor aiTaskExecutor, JsonMapper jsonMapper,
                       AiMetrics metrics, Clock clock, ShoppingCommands shoppingCommands) {
        this.shoppingCommands = shoppingCommands;
        this.agents = agents;
        this.audit = audit;
        this.conversations = conversations;
        this.chatMemory = chatMemory;
        this.chatClient = chatClient;
        this.guard = guard;
        this.contextBuilder = contextBuilder;
        this.modelStatus = modelStatus;
        this.properties = properties;
        this.executor = aiTaskExecutor;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The agent the caller may use, or 404 AGENT_NOT_FOUND / 403 AGENT_NOT_ALLOWED (audited). */
    public AgentDefinition agentFor(CurrentUser user, String agentId) {
        AgentDefinition agent = agents.find(agentId).orElseThrow(() -> ChatException.agentNotFound(agentId));
        if (!agent.allows(user)) {
            audit.agentNotAllowed(user, agent.id());
            throw ChatException.agentNotAllowed(agent.id());
        }
        return agent;
    }

    public SseEmitter chat(CurrentUser user, CallContext context, String agentId, UUID conversationId,
                           String message) {
        AgentDefinition agent = agentFor(user, agentId);
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            throw ChatException.validation("message", "must not be blank");
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw ChatException.validation("message", "size must be between 1 and " + MAX_MESSAGE_LENGTH);
        }
        java.time.Instant now = clock.instant();
        java.time.Instant windowStart = now.minus(Duration.ofHours(1));
        long recent = conversations.messagesSince(user.userId(), windowStart);
        if (recent >= properties.messagesPerHour()) {
            java.time.Instant oldest = conversations.oldestMessageSince(user.userId(), windowStart);
            long retryAfter = oldest == null ? 60
                    : Math.max(1, Duration.between(now, oldest.plus(Duration.ofHours(1))).toSeconds() + 1);
            throw ChatException.rateLimited(properties.messagesPerHour(), retryAfter);
        }
        ConversationService.Turn turn = conversations.begin(user, agent, conversationId, text);
        List<Message> history = memory(turn.conversationId());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseChatSink sink = new SseChatSink(emitter, jsonMapper);
        executor.execute(() -> run(user, context, agent, turn, history, text, sink));
        return emitter;
    }

    /** The conversation's remembered messages before this one (the new one makes the window full). */
    private List<Message> memory(UUID conversationId) {
        List<Message> remembered;
        try {
            remembered = chatMemory.get(conversationId.toString());
        } catch (RuntimeException e) {
            log.warn("Chat memory of {} unreadable, answering without it: {}", conversationId, e.toString());
            return List.of();
        }
        int keep = Math.max(0, properties.memoryMessages() - 1);
        return remembered.size() <= keep ? remembered : remembered.subList(remembered.size() - keep,
                remembered.size());
    }

    private void run(CurrentUser user, CallContext context, AgentDefinition agent, ConversationService.Turn turn,
                     List<Message> history, String text, ChatSink sink) {
        if (context.correlationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, context.correlationId());
        }
        long started = System.nanoTime();
        String outcome = AiMetrics.SUCCESS;                 // grocery_ai_model_latency_seconds{agent,outcome}
        StringBuilder answer = new StringBuilder();
        ToolTurn toolTurn = ToolTurn.of(user, context, agent, turn.conversationId(), turn.assistantMessageId(), sink);
        try {
            sink.conversation(turn.conversationId(), agent.id());
            Optional<String> commanded = command(agent, toolTurn, text, history);
            if (commanded.isPresent()) {
                // A simple shopping command, done in code: its tools ran through the guard, no model call.
                outcome = AiMetrics.COMMAND;
                answer.append(commanded.get());
                sink.token(commanded.get());
                int latency = elapsed(started);
                conversations.finish(turn, user.userId(), answer.toString(), "STOP", latency, false);
                remember(turn.conversationId(), text, answer.toString(), toolTurn.toolResults());
                sink.done(turn.assistantMessageId(), "STOP");
                log.info("Chat turn {} ({}) answered by a command in {} ms", turn.assistantMessageId(), agent.id(),
                        latency);
                return;
            }
            String contextLine = contextBuilder.build(agent, user, context);
            List<ToolCallback> tools = guard.callbacks(toolTurn);
            List<Message> fitted = fitHistory(history, tokens(agent.systemPrompt()) + tokens(contextLine)
                    + tokens(text) + toolTokens(tools), properties.contextTokens());
            if (fitted.size() < history.size()) {
                log.info("Chat turn {} ({}): {} of {} remembered messages fit the model's context window",
                        turn.assistantMessageId(), agent.id(), fitted.size(), history.size());
            }
            List<Message> messages = prompt(agent, fitted, contextLine, text);

            AtomicReference<String> finishReason = new AtomicReference<>();
            stream(messages, tools, toolTurn, sink, answer, finishReason);
            SkippedTools.Reason skipped = toolTurn.toolLimitExceeded().get() ? null
                    : notBacked(answer.toString(), text, contextLine, fitted, toolTurn);
            if (skipped != null) {
                // Once: the answer claimed a change no tool made, said it could not see data without looking, or
                // stated figures from nowhere. The client replaces the streamed text (event "retry").
                log.info("Chat turn {} ({}): the answer is not backed by a tool call ({}); asking the model once more",
                        turn.assistantMessageId(), agent.id(), skipped);
                metrics.retriedAnswer(agent.id(), skipped.name());
                sink.retry(skipped.name(), RETRY_MESSAGE);
                List<Message> again = new ArrayList<>(messages);
                again.add(new AssistantMessage(answer.toString()));
                again.add(new UserMessage(retryNudge(skipped, tools, toolTurn.toolResults())));
                answer.setLength(0);
                finishReason.set(null);
                stream(again, tools, toolTurn, sink, answer, finishReason);
            }

            if (toolTurn.toolLimitExceeded().get()) {
                throw new TurnFailed(ModelErrors.AI_TOOL_LIMIT);
            }
            int latency = elapsed(started);
            String reason = normalize(finishReason.get());
            // Still not backed after the retry: the answer is marked as not checked (stored, shown again on reopening).
            SkippedTools.Reason still = notBacked(answer.toString(), text, contextLine, fitted, toolTurn);
            boolean unverified = still != null;
            if (unverified) {
                log.warn("Chat turn {} ({}): the answer is still not backed by a tool call ({}); marked unverified",
                        turn.assistantMessageId(), agent.id(), still);
                metrics.unverifiedAnswer(agent.id());
                sink.notice(UNVERIFIED_FIGURES, UNVERIFIED_FIGURES_MESSAGE);
            }
            conversations.finish(turn, user.userId(), answer.toString(), reason, latency, unverified);
            remember(turn.conversationId(), text, answer.toString(), toolTurn.toolResults());
            sink.done(turn.assistantMessageId(), reason);
            log.info("Chat turn {} ({}) answered in {} ms", turn.assistantMessageId(), agent.id(), latency);
        } catch (ClientGone gone) {
            outcome = AiMetrics.CLIENT_GONE;
            log.info("Chat turn {}: the client went away", turn.assistantMessageId());
            conversations.fail(turn, user.userId(), "CLIENT_GONE", answer.toString(), elapsed(started));
        } catch (Throwable failure) {
            String code = failure instanceof TurnFailed turnFailed ? turnFailed.code
                    : toolTurn.toolLimitExceeded().get() ? ModelErrors.AI_TOOL_LIMIT
                    : ModelErrors.classify(failure);
            outcome = code;
            if (ModelErrors.AI_UNAVAILABLE.equals(code)) {
                modelStatus.invalidate();
                log.warn("Chat turn {} ({}): model unavailable: {}", turn.assistantMessageId(), agent.id(),
                        failure.toString());
            } else if (!ModelErrors.AI_TOOL_LIMIT.equals(code)) {
                log.warn("Chat turn {} ({}) failed with {}", turn.assistantMessageId(), agent.id(), code, failure);
            }
            try {
                conversations.fail(turn, user.userId(), code, answer.toString(), elapsed(started));
            } catch (RuntimeException storeFailure) {
                log.error("Could not record the failed turn {}", turn.assistantMessageId(), storeFailure);
            }
            sink.error(code, ModelErrors.message(code));
        } finally {
            metrics.modelCall(agent.id(), outcome, System.nanoTime() - started);
            sink.complete();
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /**
     * The shopping assistant's simple commands ("add 2 basmati rice", "show my cart", "place my order"), handled in
     * code before the model ({@link ShoppingCommands}); empty for everything else, and when they are switched off.
     */
    private Optional<String> command(AgentDefinition agent, ToolTurn toolTurn, String text, List<Message> history) {
        if (!properties.shoppingCommands() || !Agents.SHOPPING.equals(agent.id())) {
            return Optional.empty();
        }
        String lastNote = !history.isEmpty() && appNote(history.getLast()) ? history.getLast().getText() : null;
        List<String> notes = history.stream().filter(ChatService::appNote).map(Message::getText).toList();
        return shoppingCommands.handle(toolTurn, text, lastNote, notes);
    }

    /**
     * The model's messages: the assistant's rules (the same for every user, so Ollama keeps them and the tool
     * definitions that follow them read; the model's template puts the tools right after the first system message),
     * the remembered conversation, then this user's context line as a system message of its own, right before the new
     * message. With the context line inside the first system message, every changed cart, another user or a new day
     * changed the start of the prompt, and Ollama read the tools and the conversation again: 83–96 s on CPU for the
     * shopping assistant's ~3,200 tokens, against 1–5 s when the start is reused. A system message, not a user message:
     * a customer cannot type something that passes for it.
     */
    static List<Message> prompt(AgentDefinition agent, List<Message> history, String contextLine, String text) {
        List<Message> messages = new ArrayList<>(history.size() + 3);
        messages.add(new SystemMessage(agent.systemPrompt()));
        messages.addAll(history);
        messages.add(new SystemMessage(contextLine));
        messages.add(new UserMessage(text));
        return messages;
    }

    /** One streamed model call with the turn's guarded tools; tokens go to the client as they arrive. */
    private void stream(List<Message> messages, List<ToolCallback> tools, ToolTurn toolTurn, ChatSink sink,
                        StringBuilder answer, AtomicReference<String> finishReason) {
        if (!properties.modelConfigured()) {
            throw new TransientAiException("The model has no API key (ANTHROPIC_API_KEY)");    // → AI_UNAVAILABLE
        }
        chatClient.prompt()
                .messages(messages)
                .toolCallbacks(tools)
                .advisors(guard.advisor(toolTurn))
                .stream()
                .chatResponse()
                .timeout(properties.modelTimeout())
                .doOnNext(response -> forward(response, sink, toolTurn, answer, finishReason))
                .blockLast();
    }

    /** No tool was called in this turn (none succeeded, none was refused or failed). */
    private static boolean noToolCalled(ToolTurn toolTurn) {
        return toolTurn.toolResults().isEmpty() && toolTurn.toolResponses().isEmpty();
    }

    /** Starts the messages the app adds to the conversation for the model (never the customer's words). */
    static final String APP_NOTE = "[From the app, not the customer]";

    /** The {@code retry} event: the client drops the text streamed so far; a new answer follows. */
    static final String RETRY_MESSAGE = "Checking that with the app…";

    /**
     * What the model is told when its answer is sent back ({@link SkippedTools}): why, its tools by name, and what its
     * tools already returned in this turn (the first pass's tool calls are not in the retried prompt). A user message,
     * marked as coming from the app; never stored in the transcript or the memory.
     */
    static String retryNudge(SkippedTools.Reason reason, List<ToolCallback> tools, List<String> results) {
        String why = switch (reason) {
            case ACTION -> "it says something was changed, but no tool changed anything in this turn.";
            case UNSEEN -> "it says the information cannot be seen, but no tool was called to look it up.";
            case FIGURES -> "it states figures that no tool returned in this turn.";
        };
        String names = tools.stream().map(tool -> tool.getToolDefinition().name()).collect(Collectors.joining(", "));
        return APP_NOTE + " Your reply above is not backed by the app: " + why + " Call the matching tool now (your "
                + "tools: " + names + ") and answer only from its result. If no tool fits, answer without claiming "
                + "changes or figures the app did not give. Never mention tools, tool calls or this note to the "
                + "customer."
                + (results.isEmpty() ? "" : "\nWhat your tools returned in this turn:\n" + String.join("\n", results));
    }

    /** Why the answer is not backed by this turn's tool calls ({@link SkippedTools}), or null. */
    private static SkippedTools.Reason notBacked(String answer, String question, String contextLine,
                                                 List<Message> history, ToolTurn toolTurn) {
        // A prepared approval is data too (its title, expiry): such answers were wrongly marked unverified before.
        return SkippedTools.check(answer, sources(question, contextLine, history, toolTurn),
                new SkippedTools.Turn(!noToolCalled(toolTurn), toolTurn.changed().get(),
                        !toolTurn.toolResults().isEmpty() || toolTurn.changed().get()));
    }

    private static void forward(ChatResponse response, ChatSink sink, ToolTurn turn, StringBuilder answer,
                                AtomicReference<String> finishReason) {
        if (sink.isClosed()) {
            throw new ClientGone();
        }
        Generation generation = response == null ? null : response.getResult();
        if (generation == null) {
            return;
        }
        String reason = generation.getMetadata().getFinishReason();
        if (ToolCallLimitExceededException.FINISH_REASON.equals(reason)) {
            turn.toolLimitExceeded().set(true);
            return;
        }
        String text = generation.getOutput().getText();
        if (text != null && !text.isEmpty()) {
            answer.append(text);
            sink.token(text);
        }
        if (reason != null && !reason.isBlank()) {
            finishReason.set(reason);
        }
    }

    public static final String UNVERIFIED_FIGURES = "UNVERIFIED_FIGURES";
    static final String UNVERIFIED_FIGURES_MESSAGE = "This answer was not checked against the app's data: no "
            + "information was looked up for it. Ask again, or open the related page to see the real figures.";

    /**
     * What the model was given about this user, for {@link Grounding}: figures in an answer must come from here. Its
     * own earlier answers do not count (they may be invented: a tomato at ₹80 then "confirmed" the next turn); their
     * tool results do (the app's notes after them), and so do the customer's messages and the context line.
     */
    private static List<String> sources(String question, String contextLine, List<Message> history,
                                        ToolTurn toolTurn) {
        List<String> sources = new ArrayList<>();
        sources.add(question);
        sources.add(contextLine);
        history.stream().filter(message -> !(message instanceof AssistantMessage))
                .forEach(message -> sources.add(message.getText()));
        sources.addAll(toolTurn.toolResponses());              // results, refusals and errors of this turn
        return sources;
    }

    /**
     * Characters per token for the context budget. Measured with qwen3: JSON tool results with ids pack about 2.6
     * characters into a token, prose about 4; 3 keeps the estimate on the safe side for the mix the chat sends.
     */
    static final int CHARS_PER_TOKEN = 3;
    /** Room kept free in the context window for the turn's own tool results (a menu is ~2,500 tokens) and answer. */
    static final int TURN_RESERVE_TOKENS = 3000;

    /**
     * The newest remembered messages that fit the model's context window next to the system prompt, the tools and the
     * new message ({@code fixedTokens}) and {@link #TURN_RESERVE_TOKENS}; older exchanges are left out. Without this
     * Ollama cut an overlong prompt itself, from the start, where the rules and the tool definitions are, and the
     * model then answered "I've added it to your cart" without calling a tool. The kept part starts with a question.
     */
    static List<Message> fitHistory(List<Message> history, int fixedTokens, int contextTokens) {
        int budget = contextTokens - fixedTokens - TURN_RESERVE_TOKENS;
        int start = history.size();
        int used = 0;
        while (start > 0) {
            int size = tokens(history.get(start - 1).getText());
            if (used + size > budget) {
                break;
            }
            used += size;
            start--;
        }
        while (start < history.size() && (!(history.get(start) instanceof UserMessage) || appNote(history.get(start)))) {
            start++;                                           // never an answer (or its tool results) without its question
        }
        return history.subList(start, history.size());
    }

    /** Estimated tokens of a text ({@link #CHARS_PER_TOKEN}). */
    static int tokens(String text) {
        return text == null ? 0 : (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** Estimated tokens of the tool definitions the model is sent (name, description, parameter schema). */
    private static int toolTokens(List<ToolCallback> tools) {
        int total = 0;
        for (ToolCallback tool : tools) {
            ToolDefinition definition = tool.getToolDefinition();
            total += tokens(definition.name()) + tokens(definition.description()) + tokens(definition.inputSchema());
        }
        return total;
    }

    /** Total size of the tool results kept with one answer in the model's memory. */
    static final int REMEMBERED_RESULTS_LIMIT = 2500;

    /** Introduces the remembered tool results of an answer (see {@link #remember}). */
    static final String TOOL_RESULTS_NOTE = APP_NOTE + " What the tools returned for your previous answer, for "
            + "follow-up questions (use these ids; never repeat this note to the customer):\n";

    /**
     * Only completed exchanges enter the model's memory. The compact results of the turn's successful tool calls
     * follow the answer as a separate note from the app (the user never sees them; the transcript keeps the plain
     * answer): without them a follow-up such as "add the first one" makes the model guess the product id, which
     * ToolGuard refuses. They used to be appended to the answer itself, and the small model then copied the block into
     * later answers, with a tool result it never got ("[Tool results … getCart → {"items":[]}]").
     */
    private void remember(UUID conversationId, String question, String answer, List<String> toolResults) {
        List<Message> exchange = new ArrayList<>(List.of(new UserMessage(question), new AssistantMessage(answer)));
        if (!toolResults.isEmpty()) {
            String results = String.join("\n", toolResults);
            if (results.length() > REMEMBERED_RESULTS_LIMIT) {
                results = results.substring(0, REMEMBERED_RESULTS_LIMIT) + "…";
            }
            exchange.add(new UserMessage(TOOL_RESULTS_NOTE + results));
        }
        try {
            chatMemory.add(conversationId.toString(), exchange);
        } catch (RuntimeException e) {
            log.warn("Could not update the chat memory of {}: {}", conversationId, e.toString());
        }
    }

    /** A message the app added for the model, not one the customer wrote. */
    static boolean appNote(Message message) {
        return message instanceof UserMessage && message.getText() != null && message.getText().startsWith(APP_NOTE);
    }

    /** Ollama's "stop"/"length" and Claude's "end_turn"/"stop_sequence"/"max_tokens" as one vocabulary. */
    static String normalize(String finishReason) {
        String reason = finishReason == null || finishReason.isBlank() ? "STOP"
                : finishReason.trim().toUpperCase(Locale.ROOT);
        return switch (reason) {
            case "END_TURN", "STOP_SEQUENCE" -> "STOP";
            case "MAX_TOKENS" -> "LENGTH";
            default -> reason;
        };
    }

    private static int elapsed(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }

    /** The turn ends with this error code (not a model failure). */
    private static final class TurnFailed extends RuntimeException {
        private final String code;

        TurnFailed(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    /** The SSE client disconnected: stop the model stream. */
    private static final class ClientGone extends RuntimeException {
        ClientGone() {
            super("client gone", null, false, false);
        }
    }
}
