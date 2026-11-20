package com.grocery.ai.approval;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Events PUBLISHED on {@code ai.events} (contract §8, Avro schemas 1.4.0 in libs/grocery-event-schemas), aggregate
 * type {@code Approval}, key = approvalId, always in the transaction of the status change they report. The payload
 * records match the schemas field for field (the outbox converts them strictly).
 */
@Component
public class ApprovalEvents {

    public static final String TOPIC = "ai.events";
    public static final String AGGREGATE = "Approval";

    public static final String APPROVAL_REQUESTED = "ApprovalRequested";
    public static final String APPROVAL_DECIDED = "ApprovalDecided";
    public static final String APPROVAL_EXECUTED = "ApprovalExecuted";
    public static final String APPROVAL_FAILED = "ApprovalFailed";

    /** ApprovalRequested: a new request waits for a decision. */
    public record ApprovalRequested(UUID approvalId, String action, String agent, UUID requestedBy, UUID onBehalfOf,
                                    String title, UUID approverUserId, List<String> approverRoles, boolean fourEyes,
                                    BigDecimal amount, String currency, Instant expiresAt, Instant requestedAt) {
    }

    /** ApprovalDecided: APPROVED, REJECTED, EXPIRED or CANCELLED ({@code decidedBy} null for EXPIRED). */
    public record ApprovalDecided(UUID approvalId, String action, String agent, UUID requestedBy, UUID onBehalfOf,
                                  String title, String decision, UUID decidedBy, String comment, Instant decidedAt) {
    }

    /** ApprovalExecuted: the target confirmed the action (COMPLETED). */
    public record ApprovalExecuted(UUID approvalId, String action, String agent, UUID requestedBy, UUID onBehalfOf,
                                   String title, UUID decidedBy, String resultReference, Instant executedAt) {
    }

    /** ApprovalFailed: the action could not be executed (FAILED). */
    public record ApprovalFailed(UUID approvalId, String action, String agent, UUID requestedBy, UUID onBehalfOf,
                                 String title, UUID decidedBy, String errorCode, String errorMessage,
                                 Integer httpStatus, Instant failedAt) {
    }

    private final OutboxPublisher outbox;

    public ApprovalEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void requested(ApprovalRequest a) {
        publish(a, APPROVAL_REQUESTED, new ApprovalRequested(a.getId(), a.getAction().name(), a.getAgent(),
                a.getRequestedBy(), a.getOnBehalfOf(), a.getTitle(), a.getApproverUserId(), a.getApproverRoles(),
                a.isFourEyes(), a.getAmount(), a.getAmount() == null ? null : a.getCurrency(), a.getExpiresAt(),
                a.getCreatedAt()));
    }

    /** {@code decision} = APPROVED, REJECTED, EXPIRED or CANCELLED. */
    public void decided(ApprovalRequest a, String decision) {
        publish(a, APPROVAL_DECIDED, new ApprovalDecided(a.getId(), a.getAction().name(), a.getAgent(),
                a.getRequestedBy(), a.getOnBehalfOf(), a.getTitle(), decision, a.getDecidedBy(),
                a.getDecisionComment(), a.getDecidedAt()));
    }

    public void executed(ApprovalRequest a) {
        publish(a, APPROVAL_EXECUTED, new ApprovalExecuted(a.getId(), a.getAction().name(), a.getAgent(),
                a.getRequestedBy(), a.getOnBehalfOf(), a.getTitle(), a.getDecidedBy(), a.getResultReference(),
                a.getExecutedAt()));
    }

    public void failed(ApprovalRequest a) {
        publish(a, APPROVAL_FAILED, new ApprovalFailed(a.getId(), a.getAction().name(), a.getAgent(),
                a.getRequestedBy(), a.getOnBehalfOf(), a.getTitle(), a.getDecidedBy(), a.getFailureCode(),
                a.getFailureMessage(), a.getFailureHttpStatus(), a.getFailedAt()));
    }

    private void publish(ApprovalRequest approval, String eventType, Object payload) {
        outbox.publish(OutboxEvent.of(TOPIC, AGGREGATE, approval.getId().toString(), eventType, payload));
    }
}
