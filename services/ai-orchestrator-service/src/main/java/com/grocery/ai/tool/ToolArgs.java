package com.grocery.ai.tool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A tool call's arguments after ToolGuard validated them: typed values, absent ones simply missing. */
public final class ToolArgs {

    private final Map<String, Object> values;

    ToolArgs(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static ToolArgs of(Map<String, Object> values) {
        return new ToolArgs(values);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public String string(String name) {
        return (String) values.get(name);
    }

    public UUID uuid(String name) {
        return (UUID) values.get(name);
    }

    public Integer integer(String name) {
        return (Integer) values.get(name);
    }

    public BigDecimal decimal(String name) {
        return (BigDecimal) values.get(name);
    }

    public Boolean bool(String name) {
        return (Boolean) values.get(name);
    }

    public boolean isTrue(String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    public LocalDate date(String name) {
        return (LocalDate) values.get(name);
    }

    /** A list argument's entries, each validated with the list's item schema (empty when not given). */
    @SuppressWarnings("unchecked")
    public List<ToolArgs> list(String name) {
        Object value = values.get(name);
        return value == null ? List.of() : (List<ToolArgs>) value;
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
