package com.grocery.events.contract;

import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a VALID record for any schema of grocery-event-schemas, in the raw Avro representation the platform's
 * serializers use ({@code avro.use.logical.type.converters=false}): uuid as its string, timestamps as epoch
 * millis/micros/nanos, dates as epoch days, decimals as the big-endian unscaled bytes at the schema's scale, unions as
 * the branch value, arrays, maps and nested records recursively.
 *
 * <p>Values are realistic where the schema says what they are, in this order:
 * <ol>
 *   <li>an override given by field name (any depth), e.g. {@code orderType=RESTAURANT} for a restaurant consumer;</li>
 *   <li>the doc's value list for Java-enum strings ({@code "... One of: GROCERY, RESTAURANT."} → the first value);</li>
 *   <li>the field name ({@code currency} → INR, {@code orderNumber} → GA-7K3M9QX2, {@code email}, {@code phone},
 *       {@code latitude}, money-like decimals, deadlines in the future, ...);</li>
 *   <li>the doc's example ({@code "e.g. PO-7K3M9QX2"}) or alternative ({@code "APPROVED or SUSPENDED"});</li>
 *   <li>a neutral value of the type (a uuid for every uuid field: one per field name within a sample, so
 *       {@code items[].orderId} equals {@code orderId}).</li>
 * </ol>
 * {@link Variant#FULL} fills every optional ({@code ["null", T]}) field; {@link Variant#MINIMAL} leaves every optional
 * field null: a consumer must accept both, because a producer may send either.
 *
 * <p>Avro's own {@code org.apache.avro.util.RandomData} is not usable for this: it only knows the time-millis/micros
 * logical types, so a decimal gets random bytes (precision exceeded), a uuid random text and an enum-like string a
 * random value - none of which a producer could ever send.
 */
public final class EventSampleGenerator {

    /** Which optional fields are filled. */
    public enum Variant {
        /** Every {@code ["null", T]} field has a value. */
        FULL,
        /** Every {@code ["null", T]} field is null. */
        MINIMAL;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern ONE_OF = Pattern.compile("One of:\\s*([^.;]*)");
    private static final Pattern EXAMPLE = Pattern.compile("e\\.g\\.\\s*(?:'([^']+)'|([^,;()]+?))(?:[.,;)]\\s|[.,;)]?$)");
    private static final Pattern ALTERNATIVE = Pattern.compile("\\b([A-Z][A-Z0-9_]+) or ([A-Z][A-Z0-9_]+)\\b");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]*");

    /** Strings by (lower-case) field name. */
    private static final Map<String, String> STRINGS = Map.ofEntries(
            Map.entry("currency", "INR"),
            Map.entry("email", "asha.rao@example.com"),
            Map.entry("phone", "+919876543210"),
            Map.entry("contactphone", "+919876543210"),
            Map.entry("ordernumber", "GA-7K3M9QX2"),
            Map.entry("resultreference", "GA-7K3M9QX2"),
            Map.entry("returnnumber", "RT-7K3M9QX2"),
            Map.entry("purchaseordernumber", "PO-7K3M9QX2"),
            Map.entry("fullname", "Asha Rao"),
            Map.entry("partnername", "Ravi Kumar"),
            Map.entry("recipientname", "Asha Rao"),
            Map.entry("businessname", "Fresh Mart"),
            Map.entry("vendorname", "Fresh Mart"),
            Map.entry("storename", "Fresh Mart"),
            Map.entry("productname", "Toned Milk 500 ml"),
            Map.entry("name", "Toned Milk"),
            Map.entry("line1", "12 MG Road"),
            Map.entry("line2", "2nd Floor"),
            Map.entry("landmark", "Opposite City Park"),
            Map.entry("city", "Bengaluru"),
            Map.entry("state", "Karnataka"),
            Map.entry("postalcode", "560001"),
            Map.entry("country", "IN"),
            Map.entry("label", "Home"),
            Map.entry("opensat", "09:00"),
            Map.entry("closesat", "22:00"),
            Map.entry("method", "upi"),
            Map.entry("razorpayorderid", "order_Ng3x1yZ2AbCdEf"),
            Map.entry("razorpaypaymentid", "pay_Ng3x1yZ2AbCdEf"),
            Map.entry("sku", "SKU-000123"),
            Map.entry("barcode", "8901234567890"),
            Map.entry("unitlabel", "500 ml"),
            Map.entry("imageurl", "https://images.example.com/products/sample.jpg"),
            Map.entry("imageurls", "https://images.example.com/products/sample.jpg"),
            Map.entry("couponcode", "WELCOME20"),
            Map.entry("approverroles", "SUPER_ADMIN"),
            Map.entry("cuisines", "NORTH_INDIAN"),
            Map.entry("tags", "fresh"),
            Map.entry("title", "Place order: 2 items from Fresh Mart, total 249.00 INR"),
            Map.entry("errorcode", "BAD_REQUEST_ERROR"),
            Map.entry("errordescription", "The payment was declined by the bank"),
            Map.entry("errormessage", "The target service answered 502"));

    private static final Map<String, Integer> INTS = Map.ofEntries(
            Map.entry("quantity", 2), Map.entry("requested", 3), Map.entry("available", 1),
            Map.entry("onhand", 10), Map.entry("reserved", 2), Map.entry("delta", 5), Map.entry("threshold", 5),
            Map.entry("readyinminutes", 20), Map.entry("sortorder", 1), Map.entry("depth", 1),
            Map.entry("rating", 4), Map.entry("ratingcount", 3), Map.entry("itemcount", 2),
            Map.entry("attempts", 3), Map.entry("httpstatus", 502));

    private static final Map<String, Double> DOUBLES = Map.ofEntries(
            Map.entry("latitude", 12.9716), Map.entry("longitude", 77.5946), Map.entry("heading", 90.0),
            Map.entry("speedkmh", 18.5), Map.entry("accuracymeters", 8.0), Map.entry("distancekm", 2.4),
            Map.entry("tripkm", 5.8), Map.entry("radiuskm", 5.0));

    /** Money-like and other decimals by (lower-case) field name; anything else is 249.00 (fitted to precision). */
    private static final Map<String, String> DECIMALS = Map.ofEntries(
            Map.entry("averagerating", "4.00"), Map.entry("changepercent", "-10.00"),
            Map.entry("unitvalue", "500"), Map.entry("mrp", "299.00"), Map.entry("oldmrp", "299.00"),
            Map.entry("newmrp", "299.00"), Map.entry("sellingprice", "249.00"),
            Map.entry("oldsellingprice", "279.00"), Map.entry("newsellingprice", "249.00"),
            Map.entry("deliveryfee", "25.00"), Map.entry("discount", "20.00"),
            Map.entry("deliveryfeediscount", "25.00"), Map.entry("unitcost", "180.00"));

    private static final Map<String, List<Integer>> INT_ARRAYS = Map.of(
            "ratingdistribution", List.of(0, 0, 1, 1, 1));       // 3 reviews averaging 4.00 (see ratingCount)

    /** Booleans that are true in a normal event (everything else is false: late, auto, manual, fourEyes, ...). */
    private static final Set<String> TRUE_BOOLEANS = Set.of("active", "vendoractive", "categoryactive",
            "effectiveactive", "acceptingorders", "available", "instock", "restock", "fullyrefunded", "retryable");

    /** Timestamps that are deadlines (in the future) or start of a waiting period (in the past). */
    private static final Set<String> DEADLINES = Set.of("expiresat", "paymentdueat", "acceptby", "decideby", "readyby",
            "validuntil");
    private static final Set<String> PAST = Set.of("since", "cartupdatedat", "registeredat");

    private final Variant variant;
    private final Map<String, Object> overrides;
    private final Instant now;
    private final Map<String, String> uuids = new HashMap<>();

    private EventSampleGenerator(Variant variant, Map<String, ?> overrides, Instant now) {
        this.variant = variant;
        this.overrides = Map.copyOf(overrides);
        this.now = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** A valid record of {@code schema}; {@code overrides} maps field names (at any depth) to Java values. */
    public static GenericRecord generate(Schema schema, Variant variant, Map<String, ?> overrides) {
        return generate(schema, variant, overrides, Instant.now());
    }

    public static GenericRecord generate(Schema schema, Variant variant, Map<String, ?> overrides, Instant now) {
        if (schema.getType() != Schema.Type.RECORD) {
            throw new IllegalArgumentException("Event schemas are records, not " + schema.getType());
        }
        EventSampleGenerator generator = new EventSampleGenerator(variant, overrides, now);
        GenericRecord record = (GenericRecord) generator.value(schema, schema.getName(), schema.getDoc(), "");
        if (!GenericData.get().validate(schema, record)) {
            throw new IllegalStateException("Generated an invalid " + schema.getFullName() + ": " + record);
        }
        return record;
    }

    // ------------------------------------------------------------------------------------------------ generation

    private Object value(Schema schema, String field, String doc, String path) {
        String key = field.toLowerCase(Locale.ROOT);
        if (!path.isEmpty() && overrides.containsKey(field)) {
            return toAvro(overrides.get(field), schema, path);
        }
        return switch (schema.getType()) {
            case UNION -> union(schema, field, doc, path);
            case RECORD -> record(schema, path);
            case ARRAY -> array(schema, field, doc, path);
            case MAP -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("sample", value(schema.getValueType(), field, doc, path + "[sample]"));
                yield map;
            }
            case ENUM -> new GenericData.EnumSymbol(schema, enumValue(schema.getEnumSymbols(), doc));
            case STRING -> string(schema, key, doc);
            case BYTES -> isDecimal(schema) ? decimal(schema, key) : ByteBuffer.wrap(new byte[]{1, 2, 3});
            case FIXED -> fixed(schema, key);
            case INT -> intValue(schema, key);
            case LONG -> longValue(schema, key);
            case FLOAT -> (float) DOUBLES.getOrDefault(key, 1.5).doubleValue();
            case DOUBLE -> DOUBLES.getOrDefault(key, 1.5);
            case BOOLEAN -> TRUE_BOOLEANS.contains(key);
            case NULL -> null;
        };
    }

    private Object union(Schema union, String field, String doc, String path) {
        boolean nullable = union.getTypes().stream().anyMatch(branch -> branch.getType() == Schema.Type.NULL);
        if (nullable && variant == Variant.MINIMAL) {
            return null;
        }
        Schema branch = union.getTypes().stream().filter(type -> type.getType() != Schema.Type.NULL).findFirst()
                .orElse(union.getTypes().getFirst());
        return value(branch, field, doc, path);
    }

    private GenericRecord record(Schema schema, String path) {
        GenericData.Record record = new GenericData.Record(schema);
        for (Schema.Field field : schema.getFields()) {
            record.put(field.pos(), value(field.schema(), field.name(), field.doc(),
                    path.isEmpty() ? field.name() : path + "." + field.name()));
        }
        return record;
    }

    private Object array(Schema schema, String field, String doc, String path) {
        Schema element = schema.getElementType();
        List<Integer> ints = INT_ARRAYS.get(field.toLowerCase(Locale.ROOT));
        if (ints != null && element.getType() == Schema.Type.INT && element.getLogicalType() == null) {
            return new GenericData.Array<>(schema, ints);
        }
        GenericData.Array<Object> array = new GenericData.Array<>(1, schema);
        array.add(value(element, field, doc, path + "[0]"));
        return array;
    }

    private String string(Schema schema, String key, String doc) {
        LogicalType logical = schema.getLogicalType();
        if (logical != null && "uuid".equals(logical.getName())) {
            return uuids.computeIfAbsent(key, ignored -> UUID.randomUUID().toString());
        }
        if ("json".equals(schema.getProp(LogicalType.LOGICAL_TYPE_PROP))) {
            return "{}";
        }
        String oneOf = firstOfList(doc);
        if (oneOf != null) {
            return oneOf;
        }
        String known = STRINGS.get(key);
        if (known != null) {
            return known;
        }
        if (doc != null) {
            Matcher example = EXAMPLE.matcher(doc);
            if (example.find()) {
                String text = (example.group(1) != null ? example.group(1) : example.group(2)).strip();
                if (!text.isEmpty() && !text.startsWith("the ")) {
                    return text;
                }
            }
            Matcher alternative = ALTERNATIVE.matcher(doc);
            if (alternative.find()) {
                return alternative.group(1);
            }
        }
        return "Sample " + key;
    }

    private static String firstOfList(String doc) {
        if (doc == null) {
            return null;
        }
        Matcher oneOf = ONE_OF.matcher(doc);
        if (!oneOf.find()) {
            return null;
        }
        Matcher symbol = SYMBOL.matcher(oneOf.group(1));
        return symbol.find() ? symbol.group() : null;
    }

    private static String enumValue(List<String> symbols, String doc) {
        String documented = firstOfList(doc);
        return documented != null && symbols.contains(documented) ? documented : symbols.getFirst();
    }

    private Object intValue(Schema schema, String key) {
        LogicalType logical = schema.getLogicalType();
        if (logical instanceof LogicalTypes.Date) {
            return (int) LocalDate.ofInstant(now, ZoneOffset.UTC).toEpochDay();
        }
        if (logical instanceof LogicalTypes.TimeMillis) {
            return (int) Duration.ofHours(10).toMillis();
        }
        return INTS.getOrDefault(key, 1);
    }

    private Object longValue(Schema schema, String key) {
        LogicalType logical = schema.getLogicalType();
        String name = logical == null ? "" : logical.getName();
        if (name.startsWith("timestamp-") || name.startsWith("local-timestamp-")) {
            Instant at = DEADLINES.contains(key) ? now.plus(Duration.ofMinutes(20))
                    : PAST.contains(key) ? now.minus(Duration.ofMinutes(30)) : now;
            return epoch(at, name);
        }
        if ("time-micros".equals(name)) {
            return Duration.ofHours(10).toNanos() / 1_000;
        }
        return (long) INTS.getOrDefault(key, 1);
    }

    private static long epoch(Instant at, String logicalType) {
        if (logicalType.endsWith("millis")) {
            return at.toEpochMilli();
        }
        if (logicalType.endsWith("nanos")) {
            return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
        }
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L), at.getNano() / 1_000);
    }

    private static boolean isDecimal(Schema schema) {
        return schema.getLogicalType() instanceof LogicalTypes.Decimal;
    }

    private static ByteBuffer decimal(Schema schema, String key) {
        LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) schema.getLogicalType();
        return ByteBuffer.wrap(fit(new BigDecimal(DECIMALS.getOrDefault(key, "249.00")), decimal)
                .unscaledValue().toByteArray());
    }

    /** The value at the schema's scale, shrunk (by powers of ten) until it fits the precision; never zero. */
    static BigDecimal fit(BigDecimal value, LogicalTypes.Decimal decimal) {
        BigDecimal scaled = value.setScale(decimal.getScale(), RoundingMode.HALF_UP);
        while (scaled.precision() > decimal.getPrecision() && scaled.abs().compareTo(BigDecimal.ONE) > 0) {
            scaled = scaled.movePointLeft(1).setScale(decimal.getScale(), RoundingMode.HALF_UP);
        }
        if (scaled.signum() == 0 || scaled.precision() > decimal.getPrecision()) {
            scaled = BigDecimal.ONE.movePointLeft(decimal.getScale()).setScale(decimal.getScale());
        }
        return scaled;
    }

    private Object fixed(Schema schema, String key) {
        byte[] bytes = new byte[schema.getFixedSize()];
        if (isDecimal(schema)) {
            byte[] unscaled = fit(new BigDecimal(DECIMALS.getOrDefault(key, "249.00")),
                    (LogicalTypes.Decimal) schema.getLogicalType()).unscaledValue().toByteArray();
            System.arraycopy(unscaled, 0, bytes, bytes.length - unscaled.length, unscaled.length);
        } else if (schema.getLogicalType() != null && "uuid".equals(schema.getLogicalType().getName())) {
            UUID uuid = UUID.fromString(uuids.computeIfAbsent(key, ignored -> UUID.randomUUID().toString()));
            ByteBuffer.wrap(bytes).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
        }
        return new GenericData.Fixed(schema, bytes);
    }

    // ------------------------------------------------------------------------------------------------ overrides

    /**
     * A Java value (String, UUID, Instant, OffsetDateTime, LocalDate, LocalTime, BigDecimal, Number, Boolean,
     * Collection, Map, GenericRecord or null) in the raw Avro representation of {@code schema}.
     */
    public static Object toAvro(Object value, Schema schema) {
        return toAvro(value, schema, schema.getName());
    }

    private static Object toAvro(Object value, Schema schema, String path) {
        if (schema.getType() == Schema.Type.UNION) {
            if (value == null) {
                if (schema.getTypes().stream().noneMatch(branch -> branch.getType() == Schema.Type.NULL)) {
                    throw new IllegalArgumentException(path + " is not nullable");
                }
                return null;
            }
            IllegalArgumentException first = null;
            for (Schema branch : schema.getTypes()) {
                if (branch.getType() == Schema.Type.NULL) {
                    continue;
                }
                try {
                    return toAvro(value, branch, path);
                } catch (IllegalArgumentException | ClassCastException e) {
                    first = first != null ? first : new IllegalArgumentException(e.getMessage(), e);
                }
            }
            throw first != null ? first : new IllegalArgumentException(path + ": no branch for " + value);
        }
        if (value == null) {
            throw new IllegalArgumentException(path + " is required, null given");
        }
        LogicalType logical = schema.getLogicalType();
        String logicalName = logical == null ? "" : logical.getName();
        return switch (schema.getType()) {
            case STRING -> value.toString();
            case INT -> logical instanceof LogicalTypes.Date
                    ? (int) (value instanceof LocalDate date ? date : LocalDate.parse(value.toString())).toEpochDay()
                    : logical instanceof LogicalTypes.TimeMillis && value instanceof LocalTime time
                    ? (int) (time.toNanoOfDay() / 1_000_000) : ((Number) value).intValue();
            case LONG -> logicalName.contains("timestamp") ? epoch(instant(value), logicalName)
                    : ((Number) value).longValue();
            case FLOAT -> ((Number) value).floatValue();
            case DOUBLE -> ((Number) value).doubleValue();
            case BOOLEAN -> (Boolean) value;
            case BYTES -> logical instanceof LogicalTypes.Decimal decimal
                    ? ByteBuffer.wrap(new BigDecimal(value.toString()).setScale(decimal.getScale(),
                    RoundingMode.UNNECESSARY).unscaledValue().toByteArray())
                    : ByteBuffer.wrap((byte[]) value);
            case ARRAY -> {
                GenericData.Array<Object> array = new GenericData.Array<>(((Collection<?>) value).size(), schema);
                for (Object element : (Collection<?>) value) {
                    array.add(toAvro(element, schema.getElementType(), path + "[]"));
                }
                yield array;
            }
            case MAP -> {
                Map<String, Object> map = new LinkedHashMap<>();
                ((Map<?, ?>) value).forEach((k, v) -> map.put(k.toString(),
                        toAvro(v, schema.getValueType(), path + "[" + k + "]")));
                yield map;
            }
            case RECORD -> {
                if (!(value instanceof GenericRecord record) || !record.getSchema().equals(schema)) {
                    throw new IllegalArgumentException(path + " needs a " + schema.getFullName());
                }
                yield record;
            }
            case ENUM -> new GenericData.EnumSymbol(schema, value.toString());
            case FIXED -> {
                if (logical instanceof LogicalTypes.Decimal decimal) {
                    byte[] bytes = new byte[schema.getFixedSize()];
                    byte[] unscaled = new BigDecimal(value.toString()).setScale(decimal.getScale(),
                            RoundingMode.UNNECESSARY).unscaledValue().toByteArray();
                    System.arraycopy(unscaled, 0, bytes, bytes.length - unscaled.length, unscaled.length);
                    yield new GenericData.Fixed(schema, bytes);
                }
                yield new GenericData.Fixed(schema, (byte[]) value);
            }
            case NULL, UNION -> throw new IllegalArgumentException(path + ": unexpected " + schema.getType());
        };
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        return Instant.parse(value.toString());
    }

    /** The unscaled bytes of a decimal value, as the Avro binary encoding stores them (for assertions). */
    public static BigDecimal decimalOf(ByteBuffer bytes, Schema schema) {
        byte[] copy = new byte[bytes.remaining()];
        bytes.duplicate().get(copy);
        return new BigDecimal(new BigInteger(copy), ((LogicalTypes.Decimal) schema.getLogicalType()).getScale());
    }

    /** All values of a list in the doc ({@code "One of: A, B, C."}), or an empty list. */
    public static List<String> documentedValues(String doc) {
        List<String> values = new ArrayList<>();
        if (doc == null) {
            return values;
        }
        Matcher oneOf = ONE_OF.matcher(doc);
        if (oneOf.find()) {
            for (String item : oneOf.group(1).split(",")) {
                Matcher symbol = SYMBOL.matcher(item.strip());
                if (symbol.lookingAt()) {
                    values.add(symbol.group());
                }
            }
        }
        return values;
    }
}
