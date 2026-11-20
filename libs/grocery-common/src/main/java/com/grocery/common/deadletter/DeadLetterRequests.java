package com.grocery.common.deadletter;

/** Request and response bodies of the dead-letter API (contract Phase 10 §1). */
public final class DeadLetterRequests {

    /** {@code POST /{id}/discard}: {@code reason} required, at most 300 characters. */
    public record Discard(String reason) {
    }

    /** {@code POST /replay-all}: both filters optional. */
    public record ReplayAll(String consumerGroup, String eventType) {
    }

    /** {@code POST /replay-all} result. */
    public record ReplayAllResult(int attempted, int replayed, int failed) {
    }

    private DeadLetterRequests() {
    }
}
