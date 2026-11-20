package com.grocery.ai.tool;

import com.grocery.ai.approval.ApprovalService;
import com.grocery.ai.approval.ApprovalView;
import com.grocery.ai.approval.ProposalBuilder;
import com.grocery.ai.audit.AiMetrics;
import com.grocery.ai.audit.AuditLog;
import com.grocery.ai.config.AiProperties;
import com.grocery.ai.link.PageLink;
import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.id.Ids;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs before every tool call (contract §3), in this order:
 * <ol>
 *   <li>Is the tool in this agent's allowlist? Otherwise BLOCKED TOOL_NOT_ALLOWED (another agent's tool or a name the
 *       model invented — resolved to a guarded callback instead of an exception).</li>
 *   <li>May the caller's role propose it? Otherwise BLOCKED ROLE_NOT_ALLOWED.</li>
 *   <li>Are the arguments valid? Otherwise BLOCKED INVALID_ARGUMENTS.</li>
 *   <li>READ / LOW_RISK_WRITE run (through the gateway, as the caller); SENSITIVE becomes an approval request and
 *       answers APPROVAL_REQUIRED.</li>
 * </ol>
 * Every call is audited and streamed ({@code tool} STARTED, then its outcome; {@code approval} for a new request).
 * The model gets JSON: the trimmed API result, or {@code {status: APPROVAL_REQUIRED|BLOCKED|ERROR, …}}.
 */
@Component
public class ToolGuard {

    public static final String TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";
    public static final String ROLE_NOT_ALLOWED = "ROLE_NOT_ALLOWED";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String AI_TOOL_LIMIT = "AI_TOOL_LIMIT";
    private static final Logger log = LoggerFactory.getLogger(ToolGuard.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final AuditLog audit;
    /** Phase 13: {@code grocery_ai_tool_calls_total{agent,tool,status}}, counted with the audit row. */
    private final AiMetrics metrics;
    /** Phase 13: tool calls run in the span of the turn that wired them (see {@link #traced}). */
    private final ObjectProvider<Tracer> tracer;
    private final ProposalBuilder proposals;
    private final ApprovalService approvals;
    private final AiProperties properties;
    private final JsonMapper jsonMapper;

    public ToolGuard(AuditLog audit, ProposalBuilder proposals, ApprovalService approvals, AiProperties properties,
                     JsonMapper jsonMapper, AiMetrics metrics, ObjectProvider<Tracer> tracer) {
        this.audit = audit;
        this.metrics = metrics;
        this.tracer = tracer;
        this.proposals = proposals;
        this.approvals = approvals;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    // ---- Spring AI wiring for one turn ----------------------------------------------------------------------------

    /**
     * The agent's tools that the caller's role may use, each wrapped so that every call goes through this guard. The
     * model is only told about tools the user is allowed to use (a SUPPORT admin is not offered suspensions, a
     * delivery partner not the managers' tools), so it does not plan around actions it can never take. Any other
     * name the model still calls goes through {@link #resolver} and is BLOCKED and audited as before.
     */
    public List<ToolCallback> callbacks(ToolTurn turn) {
        Span turnSpan = currentSpan();
        return turn.agent().tools().stream()
                .filter(spec -> spec.mayBeUsedBy(turn.user().roles()))
                .<ToolCallback>map(spec -> new GuardedToolCallback(definition(spec), turn, this, turnSpan))
                .toList();
    }

    /** Unknown or foreign tool names: a guarded callback that blocks (and audits) instead of an exception. */
    public ToolCallbackResolver resolver(ToolTurn turn) {
        Span turnSpan = currentSpan();
        return name -> new GuardedToolCallback(DefaultToolDefinition.builder()
                .name(name == null || name.isBlank() ? "unnamed" : name)
                .description("Not a tool of this assistant.")
                .inputSchema("{\"type\":\"object\"}")
                .build(), turn, this, turnSpan);
    }

    /** The span current on this thread (the chat turn's, while the turn wires its tools), or null. */
    private Span currentSpan() {
        Tracer current = tracer.getIfAvailable();
        return current == null ? null : current.currentSpan();
    }

    /**
     * Runs one tool call (see {@link #invoke}) in {@code turnSpan}: Spring AI executes the tool calls of a streamed
     * answer on a Reactor worker thread, which carries no trace context. In the turn's span, the call's HTTP request
     * to the gateway (an observed RestClient) is part of the user's trace and sends its {@code traceparent}.
     */
    String traced(ToolTurn turn, String toolName, String rawArguments, Span turnSpan) {
        Tracer current = tracer.getIfAvailable();
        if (turnSpan == null || current == null) {
            return invoke(turn, toolName, rawArguments);
        }
        try (Tracer.SpanInScope ignored = current.withSpan(turnSpan)) {
            return invoke(turn, toolName, rawArguments);
        }
    }

    /**
     * The tool-calling loop of this turn: Spring AI's ToolCallingAdvisor over a DefaultToolCallingManager limited to
     * {@code grocery.ai.max-tool-calls} calls per user message (the next one throws), with unknown names resolved
     * through {@link #resolver}.
     */
    public ToolCallingAdvisor advisor(ToolTurn turn) {
        DefaultToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(resolver(turn))
                .resolutionFallbackEnabled(true)
                .unlimitedCallsPerTool()
                .maxTotalToolCalls(properties.maxToolCalls())
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                .build();
        return ToolCallingAdvisor.builder()
                .toolCallingManager(new GuardedToolCallingManager(manager, turn, this))
                .build();
    }

    private ToolDefinition definition(ToolSpec spec) {
        return DefaultToolDefinition.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(jsonMapper.writeValueAsString(spec.args().jsonSchema()))
                .build();
    }

    // ---- the guard ------------------------------------------------------------------------------------------------

    /** Checks, runs or proposes, audits and streams one tool call; returns what the model gets (JSON). */
    public String invoke(ToolTurn turn, String toolName, String rawArguments) {
        UUID invocationId = Ids.newId();
        long started = System.nanoTime();
        Optional<ToolSpec> found = turn.agent().tool(toolName);
        String label = found.map(ToolSpec::label).orElse(toolName);
        String previousCorrelation = MDC.get(CorrelationId.MDC_KEY);
        if (turn.http().correlationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, turn.http().correlationId());
        }
        try {
            turn.sink().tool(new ToolActivity(invocationId, toolName, label, InvocationStatus.STARTED, label + "…",
                    List.of()));
            Decision decision = decide(turn, found, toolName, rawArguments);
            int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
            audit.record(new AuditLog.Entry(invocationId, turn.conversationId(), turn.messageId(), turn.user(),
                    turn.agent().id(), toolName, auditArguments(rawArguments), decision.status(), decision.reason(),
                    decision.summary(), decision.httpStatus(), latencyMs,
                    decision.approval() == null ? null : decision.approval().id(), decision.links(),
                    decision.transport()));
            metrics.toolCall(turn.agent().id(), toolName, decision.status());
            turn.sink().tool(new ToolActivity(invocationId, toolName, label, decision.status(), decision.summary(),
                    decision.links()));
            if (decision.approval() != null) {
                turn.sink().approval(decision.approval());
            }
            if (found.isPresent() && found.get().risk() != RiskClass.READ
                    && (decision.status() == InvocationStatus.SUCCEEDED
                    || decision.status() == InvocationStatus.APPROVAL_REQUIRED)) {
                turn.changed().set(true);
            }
            String forModel = jsonMapper.writeValueAsString(decision.forModel());
            turn.toolResponses().add(forModel);
            return forModel;
        } finally {
            if (previousCorrelation == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            } else {
                MDC.put(CorrelationId.MDC_KEY, previousCorrelation);
            }
        }
    }

    /** The call that broke the tool limit: audited as BLOCKED AI_TOOL_LIMIT (it never ran). */
    void limitExceeded(ToolTurn turn, String toolName, String rawArguments) {
        audit.record(new AuditLog.Entry(Ids.newId(), turn.conversationId(), turn.messageId(), turn.user(),
                turn.agent().id(), toolName, auditArguments(rawArguments), InvocationStatus.BLOCKED, AI_TOOL_LIMIT,
                "More than " + properties.maxToolCalls() + " tool calls for one message.", null, 0, null, List.of()));
        metrics.toolCall(turn.agent().id(), toolName, InvocationStatus.BLOCKED);
    }

    /**
     * How one call ended; {@code links} (app pages showing the result) only for a SUCCEEDED call; {@code transport}
     * (REST or MCP) only for a call that reached a business service.
     */
    private record Decision(InvocationStatus status, String reason, String summary, Integer httpStatus,
                            ApprovalView approval, ObjectNode forModel, List<PageLink> links, String transport) {

        Decision(InvocationStatus status, String reason, String summary, Integer httpStatus, ApprovalView approval,
                 ObjectNode forModel, List<PageLink> links) {
            this(status, reason, summary, httpStatus, approval, forModel, links, null);
        }

        Decision via(Transport used) {
            return new Decision(status, reason, summary, httpStatus, approval, forModel, links, used.used());
        }
    }

    private Decision decide(ToolTurn turn, Optional<ToolSpec> found, String toolName, String rawArguments) {
        if (found.isEmpty()) {
            return blocked(TOOL_NOT_ALLOWED, "The tool '" + toolName + "' is not available to the "
                    + turn.agent().name() + ".");
        }
        ToolSpec spec = found.get();
        if (!spec.mayBeUsedBy(turn.user().roles())) {
            return blocked(ROLE_NOT_ALLOWED, "Your role may not use '" + toolName + "'.");
        }
        ToolArgs args;
        try {
            args = spec.args().validate(parse(rawArguments));
        } catch (InvalidArgumentsException e) {
            return blocked(INVALID_ARGUMENTS, e.getMessage());
        }
        try {
            if (spec.risk() == RiskClass.SENSITIVE) {
                return propose(turn, spec, args);
            }
            ToolCallContext call = new ToolCallContext(turn.http(), turn.user(), args);
            return switch (spec.executor().execute(call)) {
                case ToolResult.Ok ok -> {
                    turn.rememberResult(toolName, ok.result() == null ? null : ok.result().toString());
                    yield new Decision(InvocationStatus.SUCCEEDED, null, ok.summary(), ok.httpStatus(), null,
                            ok.result(), ok.links()).via(call.transport());
                }
                case ToolResult.Failed failed -> error(failed.code(), failed.message(), failed.httpStatus())
                        .via(call.transport());
            };
        } catch (RuntimeException unexpected) {
            log.error("Tool {} failed unexpectedly", toolName, unexpected);
            return error("INTERNAL_ERROR", "The tool failed unexpectedly. Please try again.", null);
        }
    }

    private Decision propose(ToolTurn turn, ToolSpec spec, ToolArgs args) {
        return switch (proposals.build(spec.action(), turn.http(), turn.user(), args)) {
            case ProposalBuilder.Outcome.Invalid invalid -> blocked(INVALID_ARGUMENTS, invalid.message());
            case ProposalBuilder.Outcome.Failed failed -> error(failed.code(), failed.message(), failed.httpStatus());
            case ProposalBuilder.Outcome.Ready ready -> {
                ApprovalView view = approvals.propose(new ApprovalService.Origin(turn.agent().id(),
                        turn.conversationId(), turn.messageId(), turn.user(), args.string("rationale")), ready.draft());
                ObjectNode forModel = NODES.objectNode();
                forModel.put("status", InvocationStatus.APPROVAL_REQUIRED.name());
                forModel.put("approvalId", view.id().toString());
                forModel.put("title", view.summary().title());
                forModel.put("expiresAt", view.expiresAt().toString());
                yield new Decision(InvocationStatus.APPROVAL_REQUIRED, null,
                        "Waiting for approval: " + view.summary().title(), null, view, forModel, List.of());
            }
        };
    }

    private static Decision blocked(String reason, String message) {
        ObjectNode forModel = NODES.objectNode();
        forModel.put("status", InvocationStatus.BLOCKED.name());
        forModel.put("reason", reason);
        forModel.put("message", message);
        return new Decision(InvocationStatus.BLOCKED, reason, message, null, null, forModel, List.of());
    }

    private static Decision error(String code, String message, Integer httpStatus) {
        ObjectNode forModel = NODES.objectNode();
        forModel.put("status", "ERROR");
        forModel.put("code", code);
        forModel.put("message", message);
        return new Decision(InvocationStatus.FAILED, code, message, httpStatus, null, forModel, List.of());
    }

    private JsonNode parse(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return NODES.objectNode();
        }
        try {
            return jsonMapper.readTree(rawArguments);
        } catch (RuntimeException e) {
            throw new InvalidArgumentsException("The arguments are not valid JSON.");
        }
    }

    /**
     * The arguments as stored in the audit, always a JSON object: the model's object as sent, or
     * {@code {"raw": "…"}} when it sent something else (not JSON, an array, a string).
     */
    private String auditArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "{}";
        }
        try {
            JsonNode parsed = jsonMapper.readTree(rawArguments);
            if (parsed != null && parsed.isObject()) {
                return jsonMapper.writeValueAsString(parsed);
            }
        } catch (RuntimeException notJson) {
            // stored as raw text below
        }
        ObjectNode raw = NODES.objectNode();
        raw.put("raw", rawArguments.length() > 2000 ? rawArguments.substring(0, 2000) : rawArguments);
        return jsonMapper.writeValueAsString(raw);
    }
}
