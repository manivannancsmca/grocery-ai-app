package com.grocery.common.deadletter;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/admin/dead-letters/{id}}: every field of {@link DeadLetterView} plus {@code payload} (the decoded
 * value as JSON; a JSON string if the value is not JSON; {@code null} if it cannot be decoded) and {@code stackTrace}
 * (the first 4 KB of the failure's stack trace).
 */
public record DeadLetterDetail(@JsonUnwrapped DeadLetterView view, JsonNode payload, String stackTrace) {
}
