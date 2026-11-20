package com.grocery.ai.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One proposed sensitive action (contract §4). Everything that will be executed — method, path, body bytes and their
 * SHA-256 — is fixed at proposal time; the summary shown to the approver is rendered by the server from those
 * parameters. Status changes go through this class and are guarded by the {@code @Version} optimistic lock, so of two
 * simultaneous decisions exactly one wins.
 */
@Entity
@Table(name = "approval_request")
public class ApprovalRequest {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private ApprovalAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalStatus status;

    @Column(nullable = false, updatable = false, length = 20)
    private String agent;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requester_roles", nullable = false, updatable = false, length = 200)
    private String requesterRoles;

    @Column(name = "on_behalf_of", updatable = false)
    private UUID onBehalfOf;

    @Column(name = "approver_user_id", updatable = false)
    private UUID approverUserId;

    /** Comma-separated; empty when {@code approverUserId} decides alone. */
    @Column(name = "approver_roles", nullable = false, updatable = false, length = 200)
    private String approverRoles;

    @Column(name = "four_eyes", nullable = false, updatable = false)
    private boolean fourEyes;

    @Column(name = "target_service", nullable = false, updatable = false, length = 40)
    private String targetService;

    @Column(name = "http_method", nullable = false, updatable = false, length = 10)
    private String httpMethod;

    @Column(name = "http_path", nullable = false, updatable = false, length = 300)
    private String httpPath;

    /** The exact body bytes (UTF-8 canonical JSON) sent on execution. */
    @Column(name = "request_body", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String requestBody;

    /** Hex SHA-256 of the body bytes at proposal time: the token's {@code bsh} and the fingerprint. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "body_sha256", nullable = false, updatable = false, length = 64)
    private String bodySha256;

    @Column(nullable = false, updatable = false, length = 200)
    private String title;

    /** JSON array of {@code {label, value}}. */
    @Column(name = "summary_lines", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String summaryLines;

    @Column(updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(updatable = false, length = 3)
    private String currency;

    @Column(updatable = false, length = 500)
    private String rationale;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_comment", length = 300)
    private String decisionComment;

    @Column(name = "executing_since")
    private Instant executingSince;

    @Column(name = "executed_at")
    private Instant executedAt;

    /** JSON object, e.g. {@code {"orderId":…,"orderNumber":…}}. */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "result_reference", length = 100)
    private String resultReference;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "failure_http_status")
    private Integer failureHttpStatus;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ApprovalRequest() {
    }

    /** A new request, {@code PENDING_APPROVAL} until {@code expiresAt}. */
    public static ApprovalRequest propose(UUID id, ApprovalAction action, String agent, UUID conversationId,
                                          UUID messageId, UUID requestedBy, String requesterRoles, UUID onBehalfOf,
                                          UUID approverUserId, List<String> approverRoles, boolean fourEyes,
                                          String httpMethod, String httpPath, String requestBody, String bodySha256,
                                          String title, String summaryLines, BigDecimal amount, String currency,
                                          String rationale, Instant now, Instant expiresAt) {
        ApprovalRequest request = new ApprovalRequest();
        request.id = id;
        request.action = action;
        request.status = ApprovalStatus.PENDING_APPROVAL;
        request.agent = agent;
        request.conversationId = conversationId;
        request.messageId = messageId;
        request.requestedBy = requestedBy;
        request.requesterRoles = requesterRoles;
        request.onBehalfOf = onBehalfOf;
        request.approverUserId = approverUserId;
        request.approverRoles = String.join(",", approverRoles);
        request.fourEyes = fourEyes;
        request.targetService = action.audience();
        request.httpMethod = httpMethod;
        request.httpPath = httpPath;
        request.requestBody = requestBody;
        request.bodySha256 = bodySha256;
        request.title = title;
        request.summaryLines = summaryLines;
        request.amount = amount;
        request.currency = currency;
        request.rationale = rationale;
        request.createdAt = now;
        request.expiresAt = expiresAt;
        request.updatedAt = now;
        return request;
    }

    // ---- transitions (callers hold the optimistic lock through @Version) -------------------------------------

    /** PENDING → APPROVED → EXECUTING in one step: the approver's decision and the start of the single execution. */
    public void approveAndStartExecution(UUID decider, String comment, Instant now) {
        requirePending();
        status = ApprovalStatus.EXECUTING;
        decidedBy = decider;
        decidedAt = now;
        decisionComment = comment;
        executingSince = now;
        updatedAt = now;
    }

    public void reject(UUID decider, String comment, Instant now) {
        requirePending();
        status = ApprovalStatus.REJECTED;
        decidedBy = decider;
        decidedAt = now;
        decisionComment = comment;
        updatedAt = now;
    }

    public void cancel(UUID by, Instant now) {
        requirePending();
        status = ApprovalStatus.CANCELLED;
        decidedBy = by;
        decidedAt = now;
        updatedAt = now;
    }

    public void expire(Instant now) {
        requirePending();
        status = ApprovalStatus.EXPIRED;
        decidedAt = now;
        updatedAt = now;
    }

    public void complete(String resultJson, String resultReference, Instant now) {
        requireExecuting();
        status = ApprovalStatus.COMPLETED;
        this.resultJson = resultJson;
        this.resultReference = resultReference;
        executedAt = now;
        updatedAt = now;
    }

    public void fail(String code, String message, Integer httpStatus, Instant now) {
        requireExecuting();
        status = ApprovalStatus.FAILED;
        failureCode = code;
        failureMessage = message == null ? null : truncate(message, 500);
        failureHttpStatus = httpStatus;
        failedAt = now;
        updatedAt = now;
    }

    private void requirePending() {
        if (status != ApprovalStatus.PENDING_APPROVAL) {
            throw ApprovalException.notPending(status);
        }
    }

    private void requireExecuting() {
        if (status != ApprovalStatus.EXECUTING) {
            throw new IllegalStateException("Approval " + id + " is " + status + ", not EXECUTING");
        }
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    // ---- getters ------------------------------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public ApprovalAction getAction() {
        return action;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getAgent() {
        return agent;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public String getRequesterRoles() {
        return requesterRoles;
    }

    public UUID getOnBehalfOf() {
        return onBehalfOf;
    }

    public UUID getApproverUserId() {
        return approverUserId;
    }

    public List<String> getApproverRoles() {
        return approverRoles == null || approverRoles.isBlank() ? List.of()
                : Arrays.stream(approverRoles.split(",")).filter(r -> !r.isBlank()).toList();
    }

    public boolean isFourEyes() {
        return fourEyes;
    }

    public String getTargetService() {
        return targetService;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getHttpPath() {
        return httpPath;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public String getBodySha256() {
        return bodySha256;
    }

    public String getTitle() {
        return title;
    }

    public String getSummaryLines() {
        return summaryLines;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getRationale() {
        return rationale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public Instant getExecutingSince() {
        return executingSince;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getResultReference() {
        return resultReference;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Integer getFailureHttpStatus() {
        return failureHttpStatus;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
