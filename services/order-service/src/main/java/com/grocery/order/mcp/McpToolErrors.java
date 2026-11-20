package com.grocery.order.mcp;

import com.grocery.common.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business error of an MCP tool as the tool result's error text: {@code {"code","message","status"}}, the same code
 * and status the REST API's problem would carry, so an MCP client can treat it like that problem. (Spring AI returns
 * the exception's message as the text of a result with {@code isError: true}.)
 */
final class McpToolErrors {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private McpToolErrors() {
    }

    static IllegalStateException of(ApiException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.errorCode());
        error.put("message", e.getMessage());
        error.put("status", e.status().value());
        return new IllegalStateException(JSON.writeValueAsString(error), e);
    }
}
