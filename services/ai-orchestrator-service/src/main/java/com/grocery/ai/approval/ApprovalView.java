package com.grocery.ai.approval;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code ApprovalView} (contract §4): what an approver sees. {@code summary} is rendered by the server from the stored
 * parameters; {@code rationale} is the assistant's own note, shown separately; {@code fingerprint} = the first 8 hex
 * characters of the body's SHA-256.
 */
public record ApprovalView(UUID id, ApprovalAction action, ApprovalStatus status, String agent, UUID conversationId,
                           UUID requestedBy, UUID onBehalfOf, UUID approverUserId, List<String> approverRoles,
                           boolean fourEyes, Summary summary, String rationale, String fingerprint,
                           boolean canDecide, Instant createdAt, Instant expiresAt, UUID decidedBy,
                           Instant decidedAt, String comment, Instant executedAt, JsonNode result,
                           Failure failure) {

    public record Summary(String title, List<Line> lines, BigDecimal amount, String currency) {
    }

    public record Line(String label, String value) {
    }

    public record Failure(String code, String message, Integer httpStatus) {
    }
}
