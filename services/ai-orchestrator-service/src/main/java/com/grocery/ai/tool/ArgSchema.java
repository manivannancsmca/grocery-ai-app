package com.grocery.ai.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One tool's parameters: the JSON schema the model sees and the validation ToolGuard applies (step 3: types, ranges,
 * lengths). Validation is lenient about representation (a small model may send {@code "2"} for 2 or {@code "true"}
 * for true) but strict about values. Unknown properties are ignored; absent, null and blank optional values are
 * "not given". Lists hold objects validated with their own schema ({@code lines[1].quantity must be …}).
 */
public final class ArgSchema {

    /** Order numbers as people type them: GA-7K3F9Q2M (upper-cased before use); also PO-… numbers. */
    public static final Pattern ORDER_NUMBER = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,39}");
    private static final Pattern ENUM_WORD = Pattern.compile("[A-Z][A-Z_]{0,39}");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Type { STRING, UUID, INTEGER, DECIMAL, BOOLEAN, DATE, ENUM, ORDER_NUMBER, LIST }

    /** Words a model uses for "no value" in an optional id argument. */
    static final Set<String> PLACEHOLDERS = Set.of("default", "none", "null", "n/a", "na", "unknown", "current",
            "primary", "default address", "my default address");

    /**
     * One parameter. For a LIST, {@code minLength}/{@code maxLength} are the entry counts and {@code items} the
     * entries' schema; {@code example} completes the error of a business number ("must be an order number such as
     * GA-7K3F9Q2M").
     */
    record Arg(String name, Type type, boolean required, String description, Integer minLength, Integer maxLength,
               BigDecimal min, BigDecimal max, Set<String> values, ArgSchema items, String example) {

        Arg(String name, Type type, boolean required, String description, Integer minLength, Integer maxLength,
            BigDecimal min, BigDecimal max, Set<String> values) {
            this(name, type, required, description, minLength, maxLength, min, max, values, null, null);
        }
    }

    private final List<Arg> args;
    private final List<Consumer<Map<String, Object>>> checks;

    private ArgSchema(List<Arg> args, List<Consumer<Map<String, Object>>> checks) {
        this.args = List.copyOf(args);
        this.checks = List.copyOf(checks);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ArgSchema none() {
        return builder().build();
    }

    /** The JSON schema of the parameters (what the model is told). */
    public ObjectNode jsonSchema() {
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = NODES.arrayNode();
        for (Arg arg : args) {
            ObjectNode property = properties.putObject(arg.name());
            switch (arg.type()) {
                case STRING, ORDER_NUMBER -> property.put("type", "string");
                case UUID -> property.put("type", "string").put("format", "uuid");
                case DATE -> property.put("type", "string").put("format", "date");
                case INTEGER -> property.put("type", "integer");
                case DECIMAL -> property.put("type", "number");
                case BOOLEAN -> property.put("type", "boolean");
                case ENUM -> {
                    property.put("type", "string");
                    ArrayNode values = property.putArray("enum");
                    arg.values().stream().sorted().forEach(values::add);
                }
                case LIST -> {
                    property.put("type", "array");
                    property.set("items", arg.items().jsonSchema());
                }
            }
            if (arg.description() != null) {
                property.put("description", arg.description());
            }
            if (arg.minLength() != null) {
                property.put(arg.type() == Type.LIST ? "minItems" : "minLength", arg.minLength());
            }
            if (arg.maxLength() != null) {
                property.put(arg.type() == Type.LIST ? "maxItems" : "maxLength", arg.maxLength());
            }
            if (arg.min() != null) {
                property.put("minimum", arg.min());
            }
            if (arg.max() != null) {
                property.put("maximum", arg.max());
            }
            if (arg.required()) {
                required.add(arg.name());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    /**
     * Validates the model's arguments.
     *
     * @throws InvalidArgumentsException naming every problem
     */
    public ToolArgs validate(JsonNode input) {
        if (input != null && !input.isNull() && !input.isObject()) {
            throw new InvalidArgumentsException("The arguments must be a JSON object.");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> problems = collect(input, "", values);
        if (!problems.isEmpty()) {
            throw new InvalidArgumentsException("Invalid arguments: " + String.join("; ", problems) + ".");
        }
        return new ToolArgs(values);
    }

    /** Parses {@code input} (an object, or nothing) into {@code values}; returns the problems, names prefixed. */
    private List<String> collect(JsonNode input, String prefix, Map<String, Object> values) {
        List<String> problems = new ArrayList<>();
        for (Arg arg : args) {
            JsonNode node = input == null || input.isNull() ? null : input.get(arg.name());
            try {
                Object value = parse(arg, node, prefix + arg.name());
                if (value == null) {
                    if (arg.required()) {
                        problems.add(prefix + arg.name() + " is required");
                    }
                } else {
                    values.put(arg.name(), value);
                }
            } catch (InvalidArgumentsException e) {
                problems.add(e.getMessage());
            }
        }
        if (problems.isEmpty()) {
            for (Consumer<Map<String, Object>> check : checks) {
                try {
                    check.accept(values);
                } catch (InvalidArgumentsException e) {
                    problems.add(prefix.isEmpty() ? e.getMessage()
                            : prefix.substring(0, prefix.length() - 1) + ": " + e.getMessage());
                }
            }
        }
        return problems;
    }

    private static Object parse(Arg arg, JsonNode node, String name) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return switch (arg.type()) {
            case STRING -> {
                String text = scalarText(name, node).strip();
                if (text.isEmpty()) {
                    yield null;
                }
                if (arg.minLength() != null && text.length() < arg.minLength()) {
                    throw new InvalidArgumentsException(name + " must have at least " + arg.minLength()
                            + " characters");
                }
                if (arg.maxLength() != null && text.length() > arg.maxLength()) {
                    throw new InvalidArgumentsException(name + " must have at most " + arg.maxLength()
                            + " characters");
                }
                yield text;
            }
            case ORDER_NUMBER -> {
                String text = scalarText(name, node).strip().toUpperCase(Locale.ROOT);
                if (text.isEmpty()) {
                    yield null;
                }
                if (!ORDER_NUMBER.matcher(text).matches()) {
                    throw new InvalidArgumentsException(name + " must be " + arg.example());
                }
                yield text;
            }
            case UUID -> {
                String text = scalarText(name, node).strip();
                if (text.isEmpty()) {
                    yield null;
                }
                // Small models fill an optional id with a word such as "default" instead of leaving it out; for an
                // optional id that means "not given" (e.g. addressId → the default address). Real junk still fails.
                if (!arg.required() && PLACEHOLDERS.contains(text.toLowerCase(Locale.ROOT))) {
                    yield null;
                }
                try {
                    yield java.util.UUID.fromString(text);
                } catch (IllegalArgumentException e) {
                    throw new InvalidArgumentsException(name + " must be an id (UUID)");
                }
            }
            case INTEGER -> {
                BigDecimal number = number(name, node);
                if (number == null) {
                    yield null;
                }
                int value;
                try {
                    value = number.intValueExact();
                } catch (ArithmeticException e) {
                    throw new InvalidArgumentsException(name + " must be a whole number");
                }
                checkRange(arg, name, BigDecimal.valueOf(value));
                yield value;
            }
            case DECIMAL -> {
                BigDecimal number = number(name, node);
                if (number == null) {
                    yield null;
                }
                BigDecimal stripped = number.stripTrailingZeros();
                if (stripped.scale() > 2) {
                    throw new InvalidArgumentsException(name + " must have at most 2 decimals");
                }
                checkRange(arg, name, number);
                yield number.setScale(2);
            }
            case BOOLEAN -> {
                if (node.isBoolean()) {
                    yield node.booleanValue();
                }
                String text = scalarText(name, node).strip().toLowerCase(Locale.ROOT);
                if (text.isEmpty()) {
                    yield null;
                }
                if (text.equals("true") || text.equals("false")) {
                    yield Boolean.parseBoolean(text);
                }
                throw new InvalidArgumentsException(name + " must be true or false");
            }
            case DATE -> {
                String text = scalarText(name, node).strip();
                if (text.isEmpty()) {
                    yield null;
                }
                try {
                    yield LocalDate.parse(text);
                } catch (DateTimeParseException e) {
                    throw new InvalidArgumentsException(name + " must be a date (yyyy-MM-dd)");
                }
            }
            case ENUM -> {
                String text = scalarText(name, node).strip().toUpperCase(Locale.ROOT);
                if (text.isEmpty()) {
                    yield null;
                }
                if (!ENUM_WORD.matcher(text).matches() || (!arg.values().isEmpty() && !arg.values().contains(text))) {
                    throw new InvalidArgumentsException(name + " must be one of " + arg.values().stream().sorted()
                            .toList());
                }
                yield text;
            }
            case LIST -> list(arg, node, name);
        };
    }

    /**
     * A list of objects. Lenient like the scalars: a single object counts as a list of one, and a list sent as a JSON
     * string is read. Empty = not given. Every entry is validated with the item schema; its problems name the entry
     * ({@code lines[1].quantity must be …}).
     */
    private static List<ToolArgs> list(Arg arg, JsonNode node, String name) {
        JsonNode array = node;
        if (array.isString()) {
            String text = array.stringValue().strip();
            if (text.isEmpty()) {
                return null;
            }
            try {
                array = JSON.readTree(text);
            } catch (RuntimeException e) {
                throw new InvalidArgumentsException(name + " must be a list");
            }
        }
        if (array.isObject()) {
            array = NODES.arrayNode().add(array);
        }
        if (!array.isArray()) {
            throw new InvalidArgumentsException(name + " must be a list");
        }
        if (array.isEmpty()) {
            return null;
        }
        if ((arg.minLength() != null && array.size() < arg.minLength())
                || (arg.maxLength() != null && array.size() > arg.maxLength())) {
            throw new InvalidArgumentsException(name + " must have between " + arg.minLength() + " and "
                    + arg.maxLength() + " entries");
        }
        List<ToolArgs> items = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            String prefix = name + "[" + i + "]";
            if (element == null || !element.isObject()) {
                problems.add(prefix + " must be an object");
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            List<String> found = arg.items().collect(element, prefix + ".", values);
            if (found.isEmpty()) {
                items.add(new ToolArgs(values));
            } else {
                problems.addAll(found);
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidArgumentsException(String.join("; ", problems));
        }
        return List.copyOf(items);
    }

    private static String scalarText(String name, JsonNode node) {
        if (node.isString() || node.isNumber() || node.isBoolean()) {
            return node.asString();
        }
        throw new InvalidArgumentsException(name + " must be a single value");
    }

    private static BigDecimal number(String name, JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isString()) {
            String text = node.stringValue().strip();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new InvalidArgumentsException(name + " must be a number");
            }
        }
        throw new InvalidArgumentsException(name + " must be a number");
    }

    private static void checkRange(Arg arg, String name, BigDecimal value) {
        if ((arg.min() != null && value.compareTo(arg.min()) < 0)
                || (arg.max() != null && value.compareTo(arg.max()) > 0)) {
            throw new InvalidArgumentsException(name + " must be between " + arg.min().toPlainString()
                    + " and " + arg.max().toPlainString());
        }
    }

    public static final class Builder {

        private final List<Arg> args = new ArrayList<>();
        private final List<Consumer<Map<String, Object>>> checks = new ArrayList<>();

        public Builder string(String name, boolean required, int minLength, int maxLength, String description) {
            args.add(new Arg(name, Type.STRING, required, description, minLength, maxLength, null, null, Set.of()));
            return this;
        }

        public Builder uuid(String name, boolean required, String description) {
            args.add(new Arg(name, Type.UUID, required, description, null, null, null, null, Set.of()));
            return this;
        }

        public Builder integer(String name, boolean required, int min, int max, String description) {
            args.add(new Arg(name, Type.INTEGER, required, description, null, null, BigDecimal.valueOf(min),
                    BigDecimal.valueOf(max), Set.of()));
            return this;
        }

        public Builder decimal(String name, boolean required, String min, String max, String description) {
            args.add(new Arg(name, Type.DECIMAL, required, description, null, null, new BigDecimal(min),
                    new BigDecimal(max), Set.of()));
            return this;
        }

        public Builder bool(String name, String description) {
            args.add(new Arg(name, Type.BOOLEAN, false, description, null, null, null, null, Set.of()));
            return this;
        }

        /** A required true / false (e.g. a dish's availability). */
        public Builder requiredBool(String name, String description) {
            args.add(new Arg(name, Type.BOOLEAN, true, description, null, null, null, null, Set.of()));
            return this;
        }

        public Builder date(String name, String description) {
            args.add(new Arg(name, Type.DATE, false, description, null, null, null, null, Set.of()));
            return this;
        }

        public Builder enumeration(String name, Set<String> values, String description) {
            args.add(new Arg(name, Type.ENUM, false, description, null, null, null, null, Set.copyOf(values)));
            return this;
        }

        /** An enumeration the model must give. */
        public Builder requiredEnumeration(String name, Set<String> values, String description) {
            args.add(new Arg(name, Type.ENUM, true, description, null, null, null, null, Set.copyOf(values)));
            return this;
        }

        public Builder orderNumber(String name, String description) {
            return reference(name, "an order number such as GA-7K3F9Q2M", description);
        }

        /**
         * A required business number as people type it (an order GA-…, a purchase order PO-…): upper-cased, letters,
         * digits and dashes. {@code example} completes the error "… must be ", e.g. "a purchase order number such as
         * PO-7K3F9Q2M".
         */
        public Builder reference(String name, String example, String description) {
            args.add(new Arg(name, Type.ORDER_NUMBER, true, description, null, 40, null, null, Set.of(), null,
                    example));
            return this;
        }

        /** A list of 1–{@code maxItems} objects, each validated with {@code items}. */
        public Builder list(String name, boolean required, int maxItems, ArgSchema items, String description) {
            args.add(new Arg(name, Type.LIST, required, description, 1, maxItems, null, null, Set.of(), items, null));
            return this;
        }

        /** The model's note for the human approver, stored as the approval's rationale (≤ 500). */
        public Builder rationale() {
            args.add(new Arg("rationale", Type.STRING, false,
                    "One short sentence for the person who approves this, explaining why you propose it.", null,
                    null, null, null, Set.of()));
            return this;
        }

        /** A rule across several arguments; throw {@link InvalidArgumentsException} to reject. */
        public Builder check(Consumer<Map<String, Object>> check) {
            checks.add(check);
            return this;
        }

        public ArgSchema build() {
            return new ArgSchema(args, checks);
        }
    }
}
