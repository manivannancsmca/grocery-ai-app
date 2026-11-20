package com.grocery.ai.audit;

import com.grocery.ai.agent.AgentDefinition;
import com.grocery.ai.agent.Agents;
import com.grocery.ai.approval.ApprovalAction;
import com.grocery.ai.config.AiProperties;
import com.grocery.ai.model.ModelStatus;
import com.grocery.ai.tool.InvocationStatus;
import com.grocery.ai.tool.ToolSpec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * ai-orchestrator-service's business metrics (docs/api/phase-13-observability-and-testing.md §1); grocery-common adds
 * the common tags and the latency timer's SLO buckets ({@code management.metrics.distribution.slo[grocery.ai.model.latency]}).
 * <ul>
 *   <li>{@code grocery_ai_tool_calls_total{agent,tool,status}} — every audited tool call: SUCCEEDED, FAILED, BLOCKED,
 *       APPROVAL_REQUIRED. {@code tool} is a name of one of the agents' tools, or {@code unknown} for a name the model
 *       invented (bounded cardinality);</li>
 *   <li>{@code grocery_ai_approvals_pending{action}} — approval requests waiting for a decision (PENDING_APPROVAL), one
 *       series per {@link ApprovalAction};</li>
 *   <li>{@code grocery_ai_model_available{provider}} — 1 when the model is usable, else 0 (the status probe of
 *       {@code GET /api/ai/status});</li>
 *   <li>{@code grocery_ai_model_latency_seconds{agent,outcome}} — one model interaction: a chat turn (the streamed answer
 *       with its tool calls) or the insights report; {@code outcome} SUCCESS, CLIENT_GONE or the error code
 *       (AI_UNAVAILABLE, AI_TIMEOUT, AI_TOOL_LIMIT, AI_ERROR).</li>
 * </ul>
 * The two gauges never query or probe on a scrape: a background thread of this instance refreshes them every
 * {@code grocery.ai.metrics.refresh-interval} (30 s, at least 15 s); a failed refresh keeps the last values.
 */
@Component
public class AiMetrics implements SmartLifecycle {

    public static final String TOOL_CALLS = "grocery.ai.tool.calls";
    public static final String APPROVALS_PENDING = "grocery.ai.approvals.pending";
    public static final String MODEL_AVAILABLE = "grocery.ai.model.available";
    public static final String MODEL_LATENCY = "grocery.ai.model.latency";
    public static final String VOICE_LATENCY = "grocery.ai.voice.latency";
    public static final String UNVERIFIED_ANSWERS = "grocery.ai.unverified.answers";
    public static final String RETRIED_ANSWERS = "grocery.ai.retried.answers";
    public static final String SUCCESS = "SUCCESS";
    public static final String CLIENT_GONE = "CLIENT_GONE";
    /** A simple shopping command answered in code, without a model call (ShoppingCommands). */
    public static final String COMMAND = "COMMAND";
    /** The {@code tool} tag of a name that is no agent's tool. */
    public static final String UNKNOWN = "unknown";

    static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(15);
    private static final Logger log = LoggerFactory.getLogger(AiMetrics.class);

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final ModelStatus modelStatus;
    private final ObjectProvider<Agents> agents;
    private final Duration refreshInterval;
    private final Map<ApprovalAction, AtomicLong> pending = new EnumMap<>(ApprovalAction.class);
    private final AtomicLong modelAvailable = new AtomicLong();
    private volatile Set<String> knownAgents;
    private volatile Set<String> knownTools;
    private volatile boolean lastRefreshFailed;
    private volatile ScheduledExecutorService scheduler;

    public AiMetrics(MeterRegistry registry, DataSource dataSource, ModelStatus modelStatus, AiProperties properties,
                     ObjectProvider<Agents> agents,
                     @Value("${grocery.ai.metrics.refresh-interval:30s}") Duration refreshInterval) {
        this.registry = registry;
        this.jdbc = JdbcClient.create(dataSource);
        this.modelStatus = modelStatus;
        this.agents = agents;
        this.refreshInterval = refreshInterval.compareTo(MIN_REFRESH_INTERVAL) < 0 ? MIN_REFRESH_INTERVAL
                : refreshInterval;
        for (ApprovalAction action : ApprovalAction.values()) {
            AtomicLong value = new AtomicLong();
            pending.put(action, value);
            Gauge.builder(APPROVALS_PENDING, value, AtomicLong::get)
                    .description("Approval requests waiting for a decision, by action")
                    .tag("action", action.name())
                    .register(registry);
        }
        Gauge.builder(MODEL_AVAILABLE, modelAvailable, AtomicLong::get)
                .description("1 when the assistants' model is usable, else 0 (the status probe)")
                .tag("provider", properties.provider().id())
                .register(registry);
    }

    /** One audited tool call of {@code agent}. */
    public void toolCall(String agent, String tool, InvocationStatus status) {
        Counter.builder(TOOL_CALLS)
                .description("AI tool calls by agent, tool and status")
                .tag("agent", agentTag(agent))
                .tag("tool", toolTag(tool))
                .tag("status", status.name())
                .register(registry)
                .increment();
    }

    /** One model interaction of {@code agent} that took {@code nanos} and ended with {@code outcome}. */
    /**
     * {@code grocery_ai_unverified_answers_total{agent}}: answers that stated figures no tool returned (the model
     * answered a data question without looking anything up); each was shown to the user as not checked.
     */
    public void unverifiedAnswer(String agent) {
        Counter.builder(UNVERIFIED_ANSWERS)
                .description("Assistant answers stating figures that no tool returned in their turn")
                .tag("agent", agentTag(agent))
                .register(registry)
                .increment();
    }

    /**
     * {@code grocery_ai_retried_answers_total{agent,reason}}: answers given without any tool call that claimed a change
     * (ACTION), said the data could not be seen (UNSEEN) or stated figures from nowhere (FIGURES); the model was asked
     * once more to use its tools (ChatService).
     */
    public void retriedAnswer(String agent, String reason) {
        Counter.builder(RETRIED_ANSWERS)
                .description("Assistant answers given without a tool call that the model was asked to redo")
                .tag("agent", agentTag(agent))
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /**
     * {@code grocery_ai_voice_latency_seconds{operation,outcome}}: one speech-to-text ({@code transcription}) or
     * text-to-speech ({@code speech}) call; {@code outcome} SUCCESS or the voice error code (bounded set).
     */
    public void voiceCall(String operation, String outcome, long nanos) {
        Timer.builder(VOICE_LATENCY)
                .description("Voice calls (speech to text, text to speech) by operation and outcome")
                .tag("operation", operation)
                .tag("outcome", outcome == null ? SUCCESS : outcome)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void modelCall(String agent, String outcome, long nanos) {
        Timer.builder(MODEL_LATENCY)
                .description("Model interactions (chat turns, insights) by agent and outcome")
                .tag("agent", agentTag(agent))
                .tag("outcome", outcome == null ? SUCCESS : outcome)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Reads the pending approvals and the model's availability now (the background thread does it every interval). */
    public void refresh() {
        try {
            Map<String, Long> counts = new HashMap<>();
            jdbc.sql("""
                            SELECT action, COUNT(*) AS n FROM approval_request
                            WHERE status = 'PENDING_APPROVAL'
                            GROUP BY action""")
                    .query((rs, row) -> counts.put(rs.getString("action"), rs.getLong("n"))).list();
            pending.forEach((action, value) -> value.set(counts.getOrDefault(action.name(), 0L)));
            if (lastRefreshFailed) {
                log.info("AI metrics refreshed again");
            }
            lastRefreshFailed = false;
        } catch (RuntimeException e) {
            if (!lastRefreshFailed) {
                log.warn("Pending approvals could not be counted (the gauge keeps its last values): {}", e.toString());
            }
            lastRefreshFailed = true;
        }
        try {
            modelAvailable.set(modelStatus.current().available() ? 1 : 0);
        } catch (RuntimeException e) {
            modelAvailable.set(0);
        }
    }

    private String agentTag(String agent) {
        Set<String> known = knownAgents;
        if (known == null) {
            known = agents.getObject().all().stream().map(AgentDefinition::id).collect(Collectors.toUnmodifiableSet());
            knownAgents = known;
        }
        return agent != null && known.contains(agent) ? agent : UNKNOWN;
    }

    private String toolTag(String tool) {
        Set<String> known = knownTools;
        if (known == null) {
            known = agents.getObject().all().stream().flatMap(agent -> agent.tools().stream())
                    .map(ToolSpec::name).collect(Collectors.toUnmodifiableSet());
            knownTools = known;
        }
        return tool != null && known.contains(tool) ? tool : UNKNOWN;
    }

    Duration refreshInterval() {
        return refreshInterval;
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("ai-metrics").daemon().factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.scheduleWithFixedDelay(this::refresh, 0, refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler = executor;
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
