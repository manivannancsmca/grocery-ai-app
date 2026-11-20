package com.grocery.ai.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One answer of a business API, whatever its status: tools and approval execution decide what a 4xx/5xx means.
 *
 * @param status HTTP status
 * @param body   the raw body (may be empty)
 * @param json   the body parsed as JSON, or null when it is empty or not JSON
 */
public record ApiResponse(int status, String body, JsonNode json) {

    static ApiResponse of(int status, String body, JsonMapper jsonMapper) {
        JsonNode json = null;
        if (body != null && !body.isBlank()) {
            try {
                json = jsonMapper.readTree(body);
            } catch (RuntimeException notJson) {
                json = null;
            }
        }
        return new ApiResponse(status, body == null ? "" : body, json);
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    /** The ProblemDetail's {@code errorCode}, or null. */
    public String errorCode() {
        return text("errorCode");
    }

    /** The ProblemDetail's {@code detail}, or null. */
    public String detail() {
        return text("detail");
    }

    private String text(String field) {
        if (json == null || !json.isObject()) {
            return null;
        }
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
