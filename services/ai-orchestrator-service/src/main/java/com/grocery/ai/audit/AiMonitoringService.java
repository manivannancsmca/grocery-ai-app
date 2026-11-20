package com.grocery.ai.audit;

import com.grocery.ai.approval.ApprovalException;
import com.grocery.ai.chat.ContextBuilder;
import com.grocery.ai.model.ModelErrors;
import com.grocery.ai.model.ModelStatus;
import com.grocery.ai.tool.InvocationStatus;
import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** The admin audit list (and its export) and the daily monitoring summary (contract §6). Dates are India dates. */
@Service
public class AiMonitoringService {

    private final ToolInvocationRepository invocations;
    private final ModelStatus modelStatus;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final ExportQueries exports;
    private final Clock clock;

    public AiMonitoringService(ToolInvocationRepository invocations, ModelStatus modelStatus, DataSource dataSource,
                               JsonMapper jsonMapper, ExportQueries exports, Clock clock) {
        this.invocations = invocations;
        this.modelStatus = modelStatus;
        this.jdbc = JdbcClient.create(dataSource);
        this.jsonMapper = jsonMapper;
        this.exports = exports;
        this.clock = clock;
    }

    public record ToolInvocationView(UUID id, UUID conversationId, UUID messageId, UUID userId, List<String> roles,
                                     String agent, String tool, JsonNode arguments, InvocationStatus status,
                                     String reason, String resultSummary, Integer httpStatus, Integer latencyMs,
                                     UUID approvalId, Instant createdAt) {
    }

    public record ApprovalCounts(long requested, long completed, long rejected, long expired, long failed,
                                 long cancelled, long pending) {
    }

    public record Summary(LocalDate date, long conversations, long messages, long toolCalls, long blocked,
                          long failed, ApprovalCounts approvals, long modelErrors, Long avgModelLatencyMs,
                          String provider, String model, boolean available) {
    }

    public PageResponse<ToolInvocationView> invocations(CurrentUser admin, String agent, UUID userId, String tool,
                                                        String status, LocalDate from, LocalDate to, Integer page,
                                                        Integer size) {
        return Pages.toResponse(invocations.findAll(invocationFilter(agent, userId, tool, status, from, to),
                Pages.request(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))), this::view);
    }

    /**
     * {@code GET /api/admin/ai/tool-invocations/export}: the audit list's filters on every page, newest first or by
     * {@code sort}, in keyset batches (no transaction here: each batch has its own).
     */
    public RowSource<ToolInvocationView> invocationsExport(String agent, UUID userId, String tool, String status,
                                                           LocalDate from, LocalDate to, String sort) {
        return exports.specification(invocations, invocationFilter(agent, userId, tool, status, from, to))
                .orderBy(AuditExports.INVOCATION_SORT.resolve(sort))
                .rows(this::view);
    }

    /** The audit list's filters (shared by the list and its export); an unknown status is 400 INVALID_PARAMETER. */
    private static Specification<ToolInvocation> invocationFilter(String agent, UUID userId, String tool,
                                                                  String status, LocalDate from, LocalDate to) {
        InvocationStatus statusFilter = parseStatus(status);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (agent != null && !agent.isBlank()) {
                predicates.add(cb.equal(root.get("agent"), agent.trim()));
            }
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (tool != null && !tool.isBlank()) {
                predicates.add(cb.equal(root.get("tool"), tool.trim()));
            }
            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOf(from)));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), startOf(to.plusDays(1))));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public Summary summary(LocalDate requested) {
        LocalDate date = requested != null ? requested : LocalDate.now(clock.withZone(ContextBuilder.INDIA));
        Map<String, Object> range = Map.of("from", utc(startOf(date)), "to", utc(startOf(date.plusDays(1))));
        long conversations = count("SELECT COUNT(*) FROM conversation WHERE created_at >= :from AND created_at < :to",
                range);
        long messages = count("SELECT COUNT(*) FROM ai_message WHERE role = 'USER' AND created_at >= :from "
                + "AND created_at < :to", range);
        long toolCalls = count("SELECT COUNT(*) FROM ai_tool_invocation WHERE tool IS NOT NULL "
                + "AND created_at >= :from AND created_at < :to", range);
        long blocked = count("SELECT COUNT(*) FROM ai_tool_invocation WHERE status = 'BLOCKED' "
                + "AND created_at >= :from AND created_at < :to", range);
        long failed = count("SELECT COUNT(*) FROM ai_tool_invocation WHERE status = 'FAILED' "
                + "AND created_at >= :from AND created_at < :to", range);
        long modelErrors = count("SELECT COUNT(*) FROM ai_message WHERE role = 'USER' AND error_code IN ('"
                + ModelErrors.AI_UNAVAILABLE + "','" + ModelErrors.AI_TIMEOUT + "','" + ModelErrors.AI_ERROR
                + "') AND created_at >= :from AND created_at < :to", range);
        Long avgLatency = jdbc.sql("SELECT ROUND(AVG(latency_ms)) FROM ai_message WHERE role = 'ASSISTANT' "
                        + "AND error_code IS NULL AND latency_ms IS NOT NULL AND created_at >= :from AND created_at < :to")
                .params(range).query(Long.class).optional().orElse(null);
        ApprovalCounts approvals = new ApprovalCounts(
                count("SELECT COUNT(*) FROM approval_request WHERE created_at >= :from AND created_at < :to", range),
                count("SELECT COUNT(*) FROM approval_request WHERE status = 'COMPLETED' AND executed_at >= :from "
                        + "AND executed_at < :to", range),
                decided("REJECTED", range),
                decided("EXPIRED", range),
                count("SELECT COUNT(*) FROM approval_request WHERE status = 'FAILED' AND failed_at >= :from "
                        + "AND failed_at < :to", range),
                decided("CANCELLED", range),
                count("SELECT COUNT(*) FROM approval_request WHERE status = 'PENDING_APPROVAL'", Map.of()));
        ModelStatus.Status status = modelStatus.current();
        return new Summary(date, conversations, messages, toolCalls, blocked, failed, approvals, modelErrors,
                avgLatency, status.provider(), status.model(), status.available());
    }

    private long decided(String status, Map<String, Object> range) {
        return count("SELECT COUNT(*) FROM approval_request WHERE status = '" + status + "' AND decided_at >= :from "
                + "AND decided_at < :to", range);
    }

    private long count(String sql, Map<String, Object> params) {
        Long value = jdbc.sql(sql).params(params).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private ToolInvocationView view(ToolInvocation i) {
        JsonNode arguments = null;
        if (i.getArguments() != null) {
            try {
                arguments = jsonMapper.readTree(i.getArguments());
            } catch (RuntimeException e) {
                arguments = null;
            }
        }
        if (arguments == null || !arguments.isObject()) {
            arguments = jsonMapper.createObjectNode();        // always an object (empty for AGENT_NOT_ALLOWED)
        }
        List<String> roles = i.getRoles() == null || i.getRoles().isBlank() ? List.of()
                : Arrays.asList(i.getRoles().split(","));
        return new ToolInvocationView(i.getId(), i.getConversationId(), i.getMessageId(), i.getUserId(), roles,
                i.getAgent(), i.getTool(), arguments, i.getStatus(), i.getReason(), i.getResultSummary(),
                i.getHttpStatus(), i.getLatencyMs(), i.getApprovalId(), i.getCreatedAt());
    }

    private static Instant startOf(LocalDate date) {
        return date.atStartOfDay(ContextBuilder.INDIA).toInstant();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static InvocationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            InvocationStatus parsed = InvocationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            if (parsed == InvocationStatus.STARTED) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw ApprovalException.invalidParameter("Unknown status '" + status + "'");
        }
    }
}
