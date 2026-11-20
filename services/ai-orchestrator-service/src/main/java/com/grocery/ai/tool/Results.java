package com.grocery.ai.tool;

import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.ai.link.PageLink;
import com.grocery.ai.support.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/** Helpers to trim API answers into what the model needs. */
final class Results {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Results() {
    }

    /** Ok → {@code ok.apply(body)}; Problem → Failed with the API's error code. */
    static ToolResult from(Fetch fetch, Function<Fetch.Ok, ToolResult> ok) {
        return switch (fetch) {
            case Fetch.Ok success -> ok.apply(success);
            case Fetch.Problem problem -> new ToolResult.Failed(problem.code(), problem.message(),
                    problem.httpStatus());
        };
    }

    /** The tool's result, with the app pages that show the same thing (see {@link com.grocery.ai.link.Pages}). */
    static ToolResult ok(ObjectNode result, String summary, Fetch.Ok fetch, PageLink... links) {
        return new ToolResult.Ok(result, summary, fetch.httpStatus(), List.of(links));
    }

    static ObjectNode object() {
        return NODES.objectNode();
    }

    /** Copies the listed fields (dotted paths allowed, stored under the last segment) when present. */
    static ObjectNode pick(JsonNode source, String... paths) {
        ObjectNode target = NODES.objectNode();
        for (String path : paths) {
            JsonNode value = Json.at(source, path);
            if (value != null) {
                String name = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                target.set(name, value.deepCopy());
            }
        }
        return target;
    }

    static ObjectNode store(JsonNode source) {
        JsonNode store = Json.at(source, "store");
        return store == null ? null : pick(store, "id", "type", "name");
    }

    static void put(ObjectNode target, String name, BigDecimal value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    static void put(ObjectNode target, String name, String value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    static ArrayNode array() {
        return NODES.arrayNode();
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
