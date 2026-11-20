package com.grocery.common.avro;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Converts event payloads between the JSON that producers write with Jackson 3 (and consumers read) and Avro generic
 * records, in both directions. The mapping is the platform contract the schema authors follow:
 *
 * <table>
 *   <caption>JSON &lt;-&gt; Avro</caption>
 *   <tr><th>Avro</th><th>JSON</th></tr>
 *   <tr><td>string</td><td>string</td></tr>
 *   <tr><td>{@code {"type":"string","logicalType":"uuid"}} (also fixed(16) uuid)</td><td>UUID string (validated)</td></tr>
 *   <tr><td>long timestamp-millis / timestamp-micros / timestamp-nanos</td>
 *       <td>ISO-8601 instant string; rendered with {@link Instant#toString()}. Digits finer than the schema's unit
 *       are truncated (an {@code Instant.now()} with nanoseconds still fits a timestamp-micros field).</td></tr>
 *   <tr><td>int date</td><td>{@code "yyyy-MM-dd"}</td></tr>
 *   <tr><td>bytes / fixed decimal(P,S)</td><td>number, rescaled to S exactly (never rounded) and checked against P;
 *       rendered as a number with S decimals, e.g. {@code 125.00}</td></tr>
 *   <tr><td>int / long / float / double</td><td>number with range checks (an int given 3.5 or 2^40 is an error;
 *       3.0 is accepted as 3). float/double also accept Jackson's {@code "NaN"}, {@code "Infinity"},
 *       {@code "-Infinity"}.</td></tr>
 *   <tr><td>boolean</td><td>boolean</td></tr>
 *   <tr><td>bytes / fixed</td><td>base64 string (what Jackson writes for {@code byte[]})</td></tr>
 *   <tr><td>enum</td><td>string (one of the symbols)</td></tr>
 *   <tr><td>array / map / record</td><td>array / object / object</td></tr>
 *   <tr><td>union</td><td>the branch matching the JSON node (in schema order); {@code ["null",T]} = null or T;
 *       nulls are rendered explicitly ({@code "field": null})</td></tr>
 *   <tr><td>{@code {"type":"string","logicalType":"json"}}</td><td>any JSON subtree, stored as its JSON text and
 *       rendered back as the subtree</td></tr>
 * </table>
 *
 * <p>JSON -&gt; Avro is strict: an unknown field, a missing field without a default, or a wrong type is an
 * {@link AvroConversionException} naming the record (event) and the field path. A missing field that has a default
 * takes the default. Avro values may be raw (as the datum reader returns them: {@code Utf8}, {@code ByteBuffer},
 * epoch numbers) or already converted ({@code BigDecimal}, {@code Instant}, {@code LocalDate}, {@code UUID}).
 *
 * <p>Thread-safe and stateless.
 */
public class JsonAvroConverter {

    /** Custom logical type for a free-form JSON subtree stored as a string. */
    public static final String JSON_LOGICAL_TYPE = "json";

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> SPECIAL_FLOATS = Set.of("NaN", "Infinity", "-Infinity");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private enum Precision { MILLIS, MICROS, NANOS }

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    /** Keeps every number exactly as written: floats as BigDecimal, trailing zeros kept. */
    private final ObjectReader exactReader = jsonMapper.readerFor(JsonNode.class)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);

    /**
     * Parses JSON text into a tree without losing precision (decimals stay {@link BigDecimal} with their scale).
     * The outbox uses it to convert exactly the JSON it stores in the {@code payload} column.
     */
    public JsonNode readExact(String json) {
        return exactReader.readTree(json);
    }

    // ------------------------------------------------------------------------------------------------ JSON -> Avro

    /** Converts a JSON object into a record of {@code schema} (strict, see the class comment). */
    public GenericRecord toRecord(JsonNode json, Schema schema) {
        if (schema.getType() != Schema.Type.RECORD) {
            throw new IllegalArgumentException("Event schemas must be records, not " + describe(schema));
        }
        return (GenericRecord) toAvro(json, schema, schema.getName(), "");
    }

    /** Converts any JSON value into the Avro generic representation of {@code schema}. */
    public Object toAvro(JsonNode json, Schema schema) {
        return toAvro(json, schema, schema.getName(), "");
    }

    private Object toAvro(JsonNode node, Schema schema, String record, String path) {
        if (schema.getType() == Schema.Type.UNION) {
            return unionToAvro(node, schema, record, path);
        }
        if (isJson(schema)) {
            return jsonMapper.writeValueAsString(isAbsent(node) ? NODES.nullNode() : node);
        }
        if (isAbsent(node)) {
            if (schema.getType() == Schema.Type.NULL) {
                return null;
            }
            throw new AvroConversionException(record, path, "null is not allowed, expected " + describe(schema));
        }
        return switch (schema.getType()) {
            case NULL -> throw mismatch(node, schema, record, path);
            case BOOLEAN -> booleanValue(node, schema, record, path);
            case INT -> isDate(schema) ? date(node, schema, record, path)
                    : (Object) (int) integral(node, schema, record, path, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case LONG -> precision(schema) != null ? timestamp(node, schema, record, path)
                    : (Object) integral(node, schema, record, path, Long.MIN_VALUE, Long.MAX_VALUE);
            case FLOAT -> (float) floatingPoint(node, schema, record, path, true);
            case DOUBLE -> floatingPoint(node, schema, record, path, false);
            case STRING -> string(node, schema, record, path);
            case BYTES -> bytes(node, schema, record, path);
            case FIXED -> fixed(node, schema, record, path);
            case ENUM -> enumSymbol(node, schema, record, path);
            case ARRAY -> array(node, schema, record, path);
            case MAP -> map(node, schema, record, path);
            case RECORD -> record(node, schema, record, path);
            case UNION -> throw new IllegalStateException("unreachable");
        };
    }

    private GenericRecord record(JsonNode node, Schema schema, String record, String path) {
        if (!node.isObject()) {
            throw mismatch(node, schema, record, path);
        }
        for (String name : node.propertyNames()) {
            if (schema.getField(name) == null) {
                throw new AvroConversionException(record, child(path, name),
                        "unknown field (not in " + schema.getFullName() + ")");
            }
        }
        GenericData.Record result = new GenericData.Record(schema);
        for (Schema.Field field : schema.getFields()) {
            JsonNode value = node.get(field.name());
            String fieldPath = child(path, field.name());
            if (value == null || value.isMissingNode()) {
                if (!field.hasDefaultValue()) {
                    throw new AvroConversionException(record, fieldPath, "missing required field (no default in "
                            + schema.getFullName() + ")");
                }
                GenericData data = GenericData.get();
                result.put(field.pos(), data.deepCopy(field.schema(), data.getDefaultValue(field)));
            } else {
                result.put(field.pos(), toAvro(value, field.schema(), record, fieldPath));
            }
        }
        return result;
    }

    private Object unionToAvro(JsonNode node, Schema union, String record, String path) {
        List<Schema> branches = union.getTypes();
        if (isAbsent(node)) {
            for (Schema branch : branches) {
                if (branch.getType() == Schema.Type.NULL) {
                    return null;
                }
            }
            throw new AvroConversionException(record, path, "null is not allowed, expected " + describe(union));
        }
        List<Schema> candidates = new ArrayList<>();
        for (Schema branch : branches) {
            if (!isJson(branch) && accepts(branch, node)) {
                candidates.add(branch);
            }
        }
        for (Schema branch : branches) {
            if (isJson(branch)) {
                candidates.add(branch);             // a JSON subtree accepts anything, so it is tried last
            }
        }
        if (candidates.isEmpty()) {
            throw mismatch(node, union, record, path);
        }
        AvroConversionException first = null;
        for (Schema candidate : candidates) {
            try {
                return toAvro(node, candidate, record, path);
            } catch (AvroConversionException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (candidates.size() == 1) {
            throw first;
        }
        throw new AvroConversionException(record, path, describe(node) + " matches no branch of " + describe(union)
                + " (" + first.detail() + ")", first);
    }

    /** Whether a union branch can take this kind of JSON node (decides which branches are tried). */
    private static boolean accepts(Schema branch, JsonNode node) {
        return switch (branch.getType()) {
            case BOOLEAN -> node.isBoolean();
            case INT -> isDate(branch) ? node.isString() : node.isNumber();
            case LONG -> precision(branch) != null ? node.isString() : node.isNumber();
            case FLOAT, DOUBLE -> node.isNumber() || (node.isString() && SPECIAL_FLOATS.contains(node.stringValue()));
            case STRING, ENUM -> node.isString();
            case BYTES, FIXED -> decimal(branch) != null ? node.isNumber() : node.isString() || node.isBinary();
            case ARRAY -> node.isArray();
            case MAP, RECORD -> node.isObject();
            case NULL, UNION -> false;
        };
    }

    private static Object booleanValue(JsonNode node, Schema schema, String record, String path) {
        if (!node.isBoolean()) {
            throw mismatch(node, schema, record, path);
        }
        return node.booleanValue();
    }

    private static long integral(JsonNode node, Schema schema, String record, String path, long min, long max) {
        if (!node.isNumber()) {
            throw mismatch(node, schema, record, path);
        }
        BigInteger value;
        if (node.isIntegralNumber()) {
            value = node.bigIntegerValue();
        } else {
            BigDecimal decimal = finiteDecimal(node, schema, record, path);
            try {
                value = decimal.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new AvroConversionException(record, path, decimal.toPlainString()
                        + " is not a whole number, expected " + describe(schema));
            }
        }
        if (value.compareTo(BigInteger.valueOf(min)) < 0 || value.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new AvroConversionException(record, path, value + " is out of range for " + describe(schema));
        }
        return value.longValue();
    }

    private static double floatingPoint(JsonNode node, Schema schema, String record, String path, boolean single) {
        double value;
        if (node.isString() && SPECIAL_FLOATS.contains(node.stringValue())) {
            value = Double.parseDouble(node.stringValue());       // Jackson writes NaN / infinities as strings
        } else if (!node.isNumber()) {
            throw mismatch(node, schema, record, path);
        } else if (node.isDouble() || node.isFloat()) {
            value = node.doubleValue();
        } else {
            value = node.decimalValue().doubleValue();
            if (Double.isInfinite(value)) {
                throw new AvroConversionException(record, path, node + " is out of range for " + describe(schema));
            }
        }
        if (single && Float.isInfinite((float) value) && !Double.isInfinite(value)) {
            throw new AvroConversionException(record, path, node + " is out of range for " + describe(schema));
        }
        return value;
    }

    private static String string(JsonNode node, Schema schema, String record, String path) {
        if (!node.isString()) {
            throw mismatch(node, schema, record, path);
        }
        String text = node.stringValue();
        if (isUuid(schema) && !UUID_PATTERN.matcher(text).matches()) {
            throw new AvroConversionException(record, path, "'" + abbreviate(text) + "' is not a UUID");
        }
        return text;
    }

    private static Object timestamp(JsonNode node, Schema schema, String record, String path) {
        if (!node.isString()) {
            throw mismatch(node, schema, record, path);
        }
        Instant instant;
        try {
            instant = Instant.parse(node.stringValue());
        } catch (DateTimeParseException e) {
            throw new AvroConversionException(record, path, "'" + abbreviate(node.stringValue())
                    + "' is not an ISO-8601 instant, expected " + describe(schema));
        }
        long seconds = instant.getEpochSecond();
        int nanos = instant.getNano();
        try {
            return switch (precision(schema)) {
                case MILLIS -> Math.addExact(Math.multiplyExact(seconds, 1_000L), nanos / 1_000_000);
                case MICROS -> Math.addExact(Math.multiplyExact(seconds, 1_000_000L), nanos / 1_000);
                case NANOS -> Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), nanos);
            };
        } catch (ArithmeticException e) {
            throw new AvroConversionException(record, path, instant + " is out of range for " + describe(schema));
        }
    }

    private static Object date(JsonNode node, Schema schema, String record, String path) {
        if (!node.isString()) {
            throw mismatch(node, schema, record, path);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(node.stringValue());
        } catch (DateTimeParseException e) {
            throw new AvroConversionException(record, path, "'" + abbreviate(node.stringValue())
                    + "' is not a date (yyyy-MM-dd)");
        }
        long day = date.toEpochDay();
        if (day < Integer.MIN_VALUE || day > Integer.MAX_VALUE) {
            throw new AvroConversionException(record, path, date + " is out of range for " + describe(schema));
        }
        return (int) day;
    }

    private static Object bytes(JsonNode node, Schema schema, String record, String path) {
        LogicalTypes.Decimal decimal = decimal(schema);
        if (decimal != null) {
            return ByteBuffer.wrap(scaledDecimal(node, schema, decimal, record, path).unscaledValue().toByteArray());
        }
        return ByteBuffer.wrap(binary(node, schema, record, path));
    }

    private static Object fixed(JsonNode node, Schema schema, String record, String path) {
        int size = schema.getFixedSize();
        LogicalTypes.Decimal decimal = decimal(schema);
        byte[] bytes;
        if (decimal != null) {
            BigDecimal scaled = scaledDecimal(node, schema, decimal, record, path);
            byte[] unscaled = scaled.unscaledValue().toByteArray();
            if (unscaled.length > size) {
                throw new AvroConversionException(record, path, scaled.toPlainString() + " does not fit "
                        + describe(schema));
            }
            bytes = new byte[size];                                   // big-endian two's complement, sign-extended
            Arrays.fill(bytes, 0, size - unscaled.length, scaled.signum() < 0 ? (byte) 0xFF : (byte) 0);
            System.arraycopy(unscaled, 0, bytes, size - unscaled.length, unscaled.length);
        } else if (isUuid(schema)) {
            UUID uuid = UUID.fromString(string(node, schema, record, path));
            bytes = ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits()).array();
        } else {
            bytes = binary(node, schema, record, path);
        }
        if (bytes.length != size) {
            throw new AvroConversionException(record, path, bytes.length + " bytes given, " + describe(schema)
                    + " needs exactly " + size);
        }
        return new GenericData.Fixed(schema, bytes);
    }

    private static BigDecimal scaledDecimal(JsonNode node, Schema schema, LogicalTypes.Decimal decimal, String record,
                                            String path) {
        if (!node.isNumber()) {
            throw mismatch(node, schema, record, path);
        }
        BigDecimal value = finiteDecimal(node, schema, record, path);
        BigDecimal scaled;
        try {
            scaled = value.setScale(decimal.getScale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new AvroConversionException(record, path, value.toPlainString() + " cannot be stored as "
                    + describe(schema) + " without rounding");
        }
        if (scaled.precision() > decimal.getPrecision()) {
            throw new AvroConversionException(record, path, value.toPlainString() + " has more digits than "
                    + describe(schema) + " allows");
        }
        return scaled;
    }

    private static BigDecimal finiteDecimal(JsonNode node, Schema schema, String record, String path) {
        if (node.isDouble() || node.isFloat()) {
            double value = node.doubleValue();
            if (!Double.isFinite(value)) {
                throw new AvroConversionException(record, path, value + " is not a finite number, expected "
                        + describe(schema));
            }
            // the shortest decimal that round-trips, e.g. 0.1 rather than its binary expansion
            return node.isFloat() ? new BigDecimal(Float.toString(node.floatValue())) : BigDecimal.valueOf(value);
        }
        return node.decimalValue();
    }

    private static byte[] binary(JsonNode node, Schema schema, String record, String path) {
        if (node.isBinary()) {
            return node.binaryValue();
        }
        if (!node.isString()) {
            throw mismatch(node, schema, record, path);
        }
        try {
            return Base64.getDecoder().decode(node.stringValue());
        } catch (IllegalArgumentException e) {
            throw new AvroConversionException(record, path, "not valid base64 for " + describe(schema));
        }
    }

    private static Object enumSymbol(JsonNode node, Schema schema, String record, String path) {
        if (!node.isString()) {
            throw mismatch(node, schema, record, path);
        }
        String symbol = node.stringValue();
        if (!schema.hasEnumSymbol(symbol)) {
            throw new AvroConversionException(record, path, "'" + abbreviate(symbol) + "' is not one of "
                    + schema.getEnumSymbols());
        }
        return new GenericData.EnumSymbol(schema, symbol);
    }

    private Object array(JsonNode node, Schema schema, String record, String path) {
        if (!node.isArray()) {
            throw mismatch(node, schema, record, path);
        }
        GenericData.Array<Object> array = new GenericData.Array<>(node.size(), schema);
        for (int i = 0; i < node.size(); i++) {
            array.add(toAvro(node.get(i), schema.getElementType(), record, path + "[" + i + "]"));
        }
        return array;
    }

    private Object map(JsonNode node, Schema schema, String record, String path) {
        if (!node.isObject()) {
            throw mismatch(node, schema, record, path);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            map.put(entry.getKey(), toAvro(entry.getValue(), schema.getValueType(), record,
                    path + "[\"" + entry.getKey() + "\"]"));
        }
        return map;
    }

    // ------------------------------------------------------------------------------------------------ Avro -> JSON

    /** Renders an Avro value of {@code schema} as the JSON tree a producer would have written. */
    public JsonNode toJson(Object value, Schema schema) {
        return toJson(value, schema, schema.getName(), "");
    }

    /** Renders an Avro value of {@code schema} as JSON text (nulls explicit, decimals with their scale). */
    public String toJsonString(Object value, Schema schema) {
        return jsonMapper.writeValueAsString(toJson(value, schema));
    }

    private JsonNode toJson(Object value, Schema schema, String record, String path) {
        if (schema.getType() == Schema.Type.UNION) {
            int branch;
            try {
                branch = GenericData.get().resolveUnion(schema, value);
            } catch (AvroRuntimeException e) {
                throw new AvroConversionException(record, path, "value does not match " + describe(schema), e);
            }
            return toJson(value, schema.getTypes().get(branch), record, path);
        }
        if (value == null) {
            if (schema.getType() == Schema.Type.NULL) {
                return NODES.nullNode();
            }
            throw new AvroConversionException(record, path, "null value for " + describe(schema));
        }
        try {
            return switch (schema.getType()) {
                case NULL -> NODES.nullNode();
                case BOOLEAN -> NODES.booleanNode((Boolean) value);
                case INT -> isDate(schema) ? NODES.stringNode(dateOf(value).toString())
                        : NODES.numberNode(((Number) value).intValue());
                case LONG -> precision(schema) != null ? NODES.stringNode(instantOf(value, precision(schema)).toString())
                        : NODES.numberNode(((Number) value).longValue());
                case FLOAT -> NODES.numberNode(((Number) value).floatValue());
                case DOUBLE -> NODES.numberNode(((Number) value).doubleValue());
                case STRING -> isJson(schema) ? parseJson(value.toString(), schema, record, path)
                        : NODES.stringNode(value.toString());
                case BYTES -> decimal(schema) != null ? DecimalNode.valueOf(decimalOf(value, decimal(schema)))
                        : NODES.stringNode(Base64.getEncoder().encodeToString(bytesOf(value)));
                case FIXED -> fixedToJson(value, schema);
                case ENUM -> NODES.stringNode(value.toString());
                case ARRAY -> arrayToJson((Collection<?>) value, schema, record, path);
                case MAP -> mapToJson((Map<?, ?>) value, schema, record, path);
                case RECORD -> recordToJson((IndexedRecord) value, schema, record, path);
                case UNION -> throw new IllegalStateException("unreachable");
            };
        } catch (ClassCastException | ArithmeticException | NumberFormatException e) {
            throw new AvroConversionException(record, path, value.getClass().getSimpleName()
                    + " is not a valid value for " + describe(schema), e);
        }
    }

    private ObjectNode recordToJson(IndexedRecord value, Schema schema, String record, String path) {
        ObjectNode node = NODES.objectNode();
        for (Schema.Field field : schema.getFields()) {
            node.set(field.name(), toJson(value.get(field.pos()), field.schema(), record, child(path, field.name())));
        }
        return node;
    }

    private ArrayNode arrayToJson(Collection<?> value, Schema schema, String record, String path) {
        ArrayNode node = NODES.arrayNode(value.size());
        int i = 0;
        for (Object element : value) {
            node.add(toJson(element, schema.getElementType(), record, path + "[" + i++ + "]"));
        }
        return node;
    }

    private ObjectNode mapToJson(Map<?, ?> value, Schema schema, String record, String path) {
        ObjectNode node = NODES.objectNode();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            String key = entry.getKey().toString();
            node.set(key, toJson(entry.getValue(), schema.getValueType(), record, path + "[\"" + key + "\"]"));
        }
        return node;
    }

    private static JsonNode fixedToJson(Object value, Schema schema) {
        byte[] bytes = value instanceof GenericFixed fixed ? fixed.bytes() : bytesOf(value);
        LogicalTypes.Decimal decimal = decimal(schema);
        if (decimal != null) {
            return DecimalNode.valueOf(value instanceof BigDecimal d ? d.setScale(decimal.getScale())
                    : new BigDecimal(new BigInteger(bytes), decimal.getScale()));
        }
        if (isUuid(schema)) {
            if (value instanceof UUID uuid) {
                return NODES.stringNode(uuid.toString());
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return NODES.stringNode(new UUID(buffer.getLong(), buffer.getLong()).toString());
        }
        return NODES.stringNode(Base64.getEncoder().encodeToString(bytes));
    }

    private JsonNode parseJson(String text, Schema schema, String record, String path) {
        JsonNode node;
        try {
            node = exactReader.readTree(text);
        } catch (JacksonException e) {
            throw new AvroConversionException(record, path, "not valid JSON text for " + describe(schema), e);
        }
        if (node == null || node.isMissingNode()) {
            throw new AvroConversionException(record, path, "empty text for " + describe(schema));
        }
        return node;
    }

    private static LocalDate dateOf(Object value) {
        return value instanceof LocalDate date ? date : LocalDate.ofEpochDay(((Number) value).intValue());
    }

    private static Instant instantOf(Object value, Precision precision) {
        if (value instanceof Instant instant) {
            return instant;
        }
        long epoch = ((Number) value).longValue();
        return switch (precision) {
            case MILLIS -> Instant.ofEpochMilli(epoch);
            case MICROS -> Instant.ofEpochSecond(Math.floorDiv(epoch, 1_000_000L), Math.floorMod(epoch, 1_000_000L) * 1_000L);
            case NANOS -> Instant.ofEpochSecond(Math.floorDiv(epoch, 1_000_000_000L), Math.floorMod(epoch, 1_000_000_000L));
        };
    }

    private static BigDecimal decimalOf(Object value, LogicalTypes.Decimal decimal) {
        if (value instanceof BigDecimal d) {
            return d.setScale(decimal.getScale());
        }
        return new BigDecimal(new BigInteger(bytesOf(value)), decimal.getScale());
    }

    private static byte[] bytesOf(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        ByteBuffer buffer = ((ByteBuffer) value).duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    // ------------------------------------------------------------------------------------------------ schema helpers

    static boolean isJson(Schema schema) {
        return schema.getType() == Schema.Type.STRING
                && JSON_LOGICAL_TYPE.equals(schema.getProp(LogicalType.LOGICAL_TYPE_PROP));
    }

    private static boolean isUuid(Schema schema) {
        return schema.getLogicalType() != null && "uuid".equals(schema.getLogicalType().getName());
    }

    private static boolean isDate(Schema schema) {
        return schema.getLogicalType() instanceof LogicalTypes.Date;
    }

    private static Precision precision(Schema schema) {
        LogicalType type = schema.getLogicalType();
        if (type instanceof LogicalTypes.TimestampMillis) {
            return Precision.MILLIS;
        }
        if (type instanceof LogicalTypes.TimestampMicros) {
            return Precision.MICROS;
        }
        if (type instanceof LogicalTypes.TimestampNanos) {
            return Precision.NANOS;
        }
        return null;
    }

    private static LogicalTypes.Decimal decimal(Schema schema) {
        return schema.getLogicalType() instanceof LogicalTypes.Decimal decimal ? decimal : null;
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static String child(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    private static AvroConversionException mismatch(JsonNode node, Schema schema, String record, String path) {
        return new AvroConversionException(record, path, "expected " + describe(schema) + " but got " + describe(node));
    }

    /** Human-readable Avro type, e.g. {@code decimal(10,2)}, {@code string (uuid)}, {@code union [null | long]}. */
    static String describe(Schema schema) {
        LogicalTypes.Decimal decimal = decimal(schema);
        if (decimal != null) {
            return "decimal(" + decimal.getPrecision() + "," + decimal.getScale() + ")";
        }
        if (isJson(schema)) {
            return "a JSON value (string, logicalType json)";
        }
        LogicalType logical = schema.getLogicalType();
        String suffix = logical != null ? " (" + logical.getName() + ")" : "";
        return switch (schema.getType()) {
            case RECORD -> "record " + schema.getName();
            case ENUM -> "enum " + schema.getName() + " " + schema.getEnumSymbols();
            case ARRAY -> "array of " + describe(schema.getElementType());
            case MAP -> "map of " + describe(schema.getValueType());
            case UNION -> schema.getTypes().stream().map(JsonAvroConverter::describe)
                    .collect(Collectors.joining(" | ", "union [", "]"));
            case FIXED -> "fixed(" + schema.getFixedSize() + ")" + suffix;
            default -> schema.getType().getName() + suffix;
        };
    }

    /** The JSON node kind; numbers and booleans with their value, strings without (they may be personal data). */
    private static String describe(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "nothing";
        }
        return switch (node.getNodeType()) {
            case NUMBER -> "the number " + node;
            case BOOLEAN -> "the boolean " + node;
            case NULL -> "null";
            case STRING -> "a string";
            case ARRAY -> "an array";
            case OBJECT -> "an object";
            case BINARY -> "binary data";
            default -> node.getNodeType().name().toLowerCase();
        };
    }

    private static String abbreviate(String text) {
        return text.length() <= 64 ? text : text.substring(0, 61) + "...";
    }
}
