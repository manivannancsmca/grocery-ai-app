package com.grocery.ai.audit;

import com.grocery.ai.tool.InvocationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One audited tool call, or one blocked agent access ({@code tool} null, reason AGENT_NOT_ALLOWED), contract §3.
 * Written once, never changed.
 */
@Entity
@Table(name = "ai_tool_invocation")
public class ToolInvocation {

    @Id
    private UUID id;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 200)
    private String roles;

    @Column(nullable = false, updatable = false, length = 40)
    private String agent;

    @Column(updatable = false, length = 100)
    private String tool;

    /** The model's arguments as JSON text. */
    @Column(updatable = false, columnDefinition = "TEXT")
    private String arguments;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private InvocationStatus status;

    @Column(updatable = false, length = 60)
    private String reason;

    @Column(name = "result_summary", updatable = false, length = 500)
    private String resultSummary;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(name = "approval_id", updatable = false)
    private UUID approvalId;

    /** The app pages the call linked to, as JSON {@code [{"label", "path"}]}; null when none (successful calls only). */
    @Column(name = "page_links", updatable = false, columnDefinition = "TEXT")
    private String pageLinks;

    /** REST or MCP (the MCP pilot): how the call reached the business service; null when it did not (blocked). */
    @Column(name = "transport", updatable = false, length = 10)
    private String transport;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ToolInvocation() {
    }

    public ToolInvocation(UUID id, UUID conversationId, UUID messageId, UUID userId, String roles, String agent,
                          String tool, String arguments, InvocationStatus status, String reason, String resultSummary,
                          Integer httpStatus, Integer latencyMs, UUID approvalId, String pageLinks,
                          Instant createdAt) {
        this(id, conversationId, messageId, userId, roles, agent, tool, arguments, status, reason, resultSummary,
                httpStatus, latencyMs, approvalId, pageLinks, null, createdAt);
    }

    public ToolInvocation(UUID id, UUID conversationId, UUID messageId, UUID userId, String roles, String agent,
                          String tool, String arguments, InvocationStatus status, String reason, String resultSummary,
                          Integer httpStatus, Integer latencyMs, UUID approvalId, String pageLinks, String transport,
                          Instant createdAt) {
        this.transport = transport;
        this.id = id;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.userId = userId;
        this.roles = roles;
        this.agent = agent;
        this.tool = tool;
        this.arguments = arguments;
        this.status = status;
        this.reason = reason;
        this.resultSummary = resultSummary == null || resultSummary.length() <= 500 ? resultSummary
                : resultSummary.substring(0, 499) + "…";
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
        this.approvalId = approvalId;
        this.pageLinks = pageLinks;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRoles() {
        return roles;
    }

    public String getAgent() {
        return agent;
    }

    public String getTool() {
        return tool;
    }

    public String getArguments() {
        return arguments;
    }

    public InvocationStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public UUID getApprovalId() {
        return approvalId;
    }

    public String getPageLinks() {
        return pageLinks;
    }

    public String getTransport() {
        return transport;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
