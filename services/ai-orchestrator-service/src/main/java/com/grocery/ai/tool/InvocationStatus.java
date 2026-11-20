package com.grocery.ai.tool;

/**
 * The status of one tool call: {@code STARTED} is only streamed (the {@code tool} SSE event before the call runs);
 * every audited call ends in one of the other four.
 */
public enum InvocationStatus {
    STARTED, SUCCEEDED, FAILED, BLOCKED, APPROVAL_REQUIRED
}
