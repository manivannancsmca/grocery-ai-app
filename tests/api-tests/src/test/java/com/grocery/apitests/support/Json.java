package com.grocery.apitests.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers: request bodies as ordered maps, and a shared Jackson mapper (decimals stay BigDecimal). */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Json() {
    }

    /** {@code obj("a", 1, "b", "x")} → an ordered map; null values are kept (explicit JSON null). */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs: " + Arrays.toString(keyValues));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public static List<Object> arr(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise " + value, e);
        }
    }

    public static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not JSON: " + abbreviate(json, 300), e);
        }
    }

    public static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    public static String abbreviate(String text, int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(" + text.length() + " chars)";
    }
}
