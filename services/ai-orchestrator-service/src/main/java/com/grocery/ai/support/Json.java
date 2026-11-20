package com.grocery.ai.support;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Null-safe reads of the business APIs' JSON answers. */
public final class Json {

    private Json() {
    }

    public static JsonNode at(JsonNode node, String path) {
        if (node == null) {
            return null;
        }
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            current = current == null || current.isNull() ? null : current.get(part);
        }
        return current == null || current.isNull() || current.isMissingNode() ? null : current;
    }

    public static String text(JsonNode node, String path) {
        JsonNode value = at(node, path);
        return value == null ? null : value.asString();
    }

    public static BigDecimal decimal(JsonNode node, String path) {
        JsonNode value = at(node, path);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer integer(JsonNode node, String path) {
        JsonNode value = at(node, path);
        return value == null || !value.isNumber() ? null : value.intValue();
    }

    public static boolean bool(JsonNode node, String path) {
        JsonNode value = at(node, path);
        return value != null && value.asBoolean();
    }

    public static UUID uuid(JsonNode node, String path) {
        String text = text(node, path);
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The elements of an array (none when absent). */
    public static List<JsonNode> list(JsonNode node, String path) {
        JsonNode value = at(node, path);
        List<JsonNode> items = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(items::add);
        }
        return items;
    }
}
