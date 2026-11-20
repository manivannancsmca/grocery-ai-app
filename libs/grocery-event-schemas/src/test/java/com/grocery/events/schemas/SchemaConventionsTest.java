package com.grocery.events.schemas;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import org.apache.avro.JsonProperties;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conventions every schema follows (README "Conventions"): layout and naming, documentation, nullability,
 * logical types, no Avro enums, self-contained files. Each rule runs for every packaged schema.
 */
class SchemaConventionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");

    /** logicalType -> the only Avro type it may annotate. {@code json} is a custom marker (JSON text in a string). */
    private static final Map<String, Schema.Type> LOGICAL_TYPES = Map.of(
            "uuid", Schema.Type.STRING,
            "timestamp-micros", Schema.Type.LONG,
            "date", Schema.Type.INT,
            "decimal", Schema.Type.BYTES,
            "json", Schema.Type.STRING);

    private static final Set<Schema.Type> ALLOWED_TYPES = Set.of(Schema.Type.RECORD, Schema.Type.ARRAY,
            Schema.Type.MAP, Schema.Type.UNION, Schema.Type.STRING, Schema.Type.BYTES, Schema.Type.INT,
            Schema.Type.LONG, Schema.Type.FLOAT, Schema.Type.DOUBLE, Schema.Type.BOOLEAN, Schema.Type.NULL);

    static List<EventSchemaFile> schemas() {
        return EventSchemaFile.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void parsesWithConfluentAvroSchemaAndPlainAvroWithoutReferences(EventSchemaFile file) {
        AvroSchema schema = file.parse();
        schema.validate(true);
        assertThat(schema.references()).as("schemas are self-contained, no references to other files").isEmpty();
        // A fresh plain Avro parser knows no other types: any name not defined in this file would fail here.
        // It also validates every default value against its field type.
        Schema plain = new Schema.Parser().parse(file.text());
        assertThat(plain).isEqualTo(schema.rawSchema());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void recordNameAndNamespaceFollowFromFileAndTopic(EventSchemaFile file) {
        Schema schema = file.parse().rawSchema();
        assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(schema.getName()).as("record name = file name").isEqualTo(file.expectedName());
        assertThat(schema.getNamespace()).as("namespace = com.grocery.events.<topic before the first dot>")
                .isEqualTo(file.expectedNamespace());
        assertThat(schema.getFullName()).isEqualTo(file.fullName());
        assertThat(file.subject()).isEqualTo(file.topic() + "-" + schema.getFullName());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void topLevelKeysStartWithTypeNameNamespace(EventSchemaFile file) {
        JsonNode root = JSON.readTree(file.text());
        assertThat(root.isObject()).isTrue();
        assertThat(new ArrayList<>(root.propertyNames()).subList(0, 3)).containsExactly("type", "name", "namespace");
        assertThat(root.get("type").stringValue()).isEqualTo("record");
        assertThat(root.get("name").stringValue()).isEqualTo(file.expectedName());
        assertThat(root.get("namespace").stringValue()).isEqualTo(file.expectedNamespace());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void topLevelDocNamesTheTopic(EventSchemaFile file) {
        assertThat(file.parse().rawSchema().getDoc()).contains("topic " + file.topic());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void everyRecordAndEveryFieldHasADoc(EventSchemaFile file) {
        List<String> missing = new ArrayList<>();
        forEachRecord(file.parse().rawSchema(), record -> {
            if (isBlank(record.getDoc())) {
                missing.add("record " + record.getFullName());
            }
            for (Schema.Field field : record.getFields()) {
                if (isBlank(field.doc())) {
                    missing.add(record.getName() + "." + field.name());
                }
            }
        });
        assertThat(missing).as("schema elements without a doc").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void fieldNamesAreCamelCase(EventSchemaFile file) {
        List<String> bad = new ArrayList<>();
        forEachRecord(file.parse().rawSchema(), record -> record.getFields().forEach(field -> {
            if (!FIELD_NAME.matcher(field.name()).matches()) {
                bad.add(record.getName() + "." + field.name());
            }
        }));
        assertThat(bad).as("field names must be the camelCase JSON property names").isEmpty();
    }

    /**
     * Nullability: a union is only ever {@code ["null", T]}, used directly as a field type, with
     * {@code "default": null}. A union anywhere else (array items, map values) has no default and is not part of
     * the type mapping.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void nullableFieldsAreNullFirstUnionsWithDefaultNull(EventSchemaFile file) {
        List<String> bad = new ArrayList<>();
        forEachRecord(file.parse().rawSchema(), record -> record.getFields().forEach(field -> {
            Schema type = field.schema();
            String where = record.getName() + "." + field.name();
            if (type.getType() == Schema.Type.UNION) {
                List<Schema> branches = type.getTypes();
                if (branches.size() != 2 || branches.get(0).getType() != Schema.Type.NULL
                        || branches.get(1).getType() == Schema.Type.NULL) {
                    bad.add(where + ": a union must be exactly [\"null\", T], null first");
                }
                if (!field.hasDefaultValue() || field.defaultVal() != JsonProperties.NULL_VALUE) {
                    bad.add(where + ": a nullable field needs \"default\": null");
                }
            }
        }));
        forEachSchema(file.parse().rawSchema(), (schema, position) -> {
            if (schema.getType() == Schema.Type.UNION && !position.equals("field")) {
                bad.add("union used as " + position + ": only fields may be nullable");
            }
        });
        assertThat(bad).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void noAvroEnumsOrOtherTypesOutsideTheMapping(EventSchemaFile file) {
        List<String> bad = new ArrayList<>();
        forEachSchema(file.parse().rawSchema(), (schema, position) -> {
            if (!ALLOWED_TYPES.contains(schema.getType())) {
                bad.add(schema.getType() + " " + schema.getName()
                        + (schema.getType() == Schema.Type.ENUM ? " (Java enums are strings: list the values in doc)"
                        : ""));
            }
            if (schema.getType() == Schema.Type.NULL && !position.equals("union branch")) {
                bad.add("null used outside a [\"null\", T] union");
            }
        });
        assertThat(bad).isEmpty();
    }

    /**
     * Every {@code logicalType} is one of the mapping's and is recognised by Avro: Avro silently ignores an invalid
     * one (typo, wrong base type, scale > precision), which would turn a UUID or a decimal into a plain value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void logicalTypesAreValidAndFromTheMapping(EventSchemaFile file) {
        List<String> bad = new ArrayList<>();
        forEachSchema(file.parse().rawSchema(), (schema, position) -> {
            String declared = schema.getProp(LogicalType.LOGICAL_TYPE_PROP);
            LogicalType parsed = schema.getLogicalType();
            if (declared == null) {
                if (schema.getType() == Schema.Type.BYTES) {
                    bad.add("bytes without logicalType decimal (bytes are only used for BigDecimal)");
                }
                return;
            }
            Schema.Type expected = LOGICAL_TYPES.get(declared);
            if (expected == null) {
                bad.add("logicalType '" + declared + "' is not part of the type mapping");
            } else if (schema.getType() != expected) {
                bad.add("logicalType '" + declared + "' must annotate " + expected + ", not " + schema.getType());
            } else if (!declared.equals("json") && (parsed == null || !parsed.getName().equals(declared))) {
                bad.add("logicalType '" + declared + "' is invalid and ignored by Avro: " + schema);
            }
        });
        assertThat(bad).isEmpty();
    }

    /** Decimals state precision AND scale explicitly (Avro would default a missing scale to 0). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void decimalsDeclarePrecisionAndScale(EventSchemaFile file) {
        List<String> bad = new ArrayList<>();
        forEachJsonObject(JSON.readTree(file.text()), node -> {
            if (node.has("logicalType") && "decimal".equals(node.get("logicalType").stringValue())) {
                if (!node.path("precision").isInt() || !node.path("scale").isInt()) {
                    bad.add("decimal without explicit integer precision and scale: " + node);
                }
            }
        });
        forEachSchema(file.parse().rawSchema(), (schema, position) -> {
            if (schema.getLogicalType() instanceof LogicalTypes.Decimal decimal
                    && (decimal.getPrecision() < 1 || decimal.getScale() < 0
                    || decimal.getScale() > decimal.getPrecision())) {
                bad.add("invalid decimal(" + decimal.getPrecision() + "," + decimal.getScale() + ")");
            }
        });
        assertThat(bad).isEmpty();
    }

    /**
     * Nested records are defined inline in the file that uses them, in the event's namespace, named
     * {@code <EventType><Thing>}; that keeps every full name unique across the whole module.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void nestedRecordsAreInlineAndPrefixedWithTheEventName(EventSchemaFile file) {
        Schema top = file.parse().rawSchema();
        List<String> bad = new ArrayList<>();
        forEachRecord(top, record -> {
            if (record != top && (!record.getNamespace().equals(top.getNamespace())
                    || !record.getName().startsWith(top.getName()) || record.getName().equals(top.getName()))) {
                bad.add(record.getFullName());
            }
        });
        assertThat(bad).as("nested records must be %s.%s<Thing>", top.getNamespace(), top.getName()).isEmpty();
    }

    /**
     * The file is pure ASCII without tabs: register-schemas.sh only strips line breaks and escapes quotes and
     * backslashes before embedding the schema in a JSON string.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void fileIsPlainAscii(EventSchemaFile file) {
        String text = file.text();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = c == '\n' || c == '\r' || (c >= 0x20 && c < 0x7f);
            assertThat(ok).as("%s: character U+%04X at offset %d", file, (int) c, i).isTrue();
        }
    }

    @Test
    void everyNamedTypeIsUniqueAcrossAllSchemas() {
        Map<String, String> definedIn = new HashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (EventSchemaFile file : schemas()) {
            forEachRecord(file.parse().rawSchema(), record -> {
                String previous = definedIn.putIfAbsent(record.getFullName(), file.id());
                if (previous != null && !previous.equals(file.id())) {
                    duplicates.add(record.getFullName() + " in " + previous + " and " + file.id());
                }
            });
        }
        assertThat(duplicates).isEmpty();
    }

    // ---- schema walking --------------------------------------------------------------------------------------

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** Visits every record once (top level first). */
    static void forEachRecord(Schema root, java.util.function.Consumer<Schema> visitor) {
        forEachSchema(root, (schema, position) -> {
            if (schema.getType() == Schema.Type.RECORD) {
                visitor.accept(schema);
            }
        });
    }

    /**
     * Visits every schema node with its position: {@code root}, {@code field}, {@code union branch},
     * {@code array items} or {@code map values}. Named types referenced again are visited once.
     */
    static void forEachSchema(Schema root, BiConsumer<Schema, String> visitor) {
        walk(root, "root", visitor, new IdentityHashMap<>());
    }

    private static void walk(Schema schema, String position, BiConsumer<Schema, String> visitor,
                             IdentityHashMap<Schema, Boolean> seen) {
        boolean named = schema.getType() == Schema.Type.RECORD || schema.getType() == Schema.Type.ENUM
                || schema.getType() == Schema.Type.FIXED;
        if (named && seen.put(schema, Boolean.TRUE) != null) {
            return;
        }
        visitor.accept(schema, position);
        switch (schema.getType()) {
            case RECORD -> schema.getFields().forEach(field -> walk(field.schema(), "field", visitor, seen));
            case UNION -> schema.getTypes().forEach(branch -> walk(branch, "union branch", visitor, seen));
            case ARRAY -> walk(schema.getElementType(), "array items", visitor, seen);
            case MAP -> walk(schema.getValueType(), "map values", visitor, seen);
            default -> {
            }
        }
    }

    private static void forEachJsonObject(JsonNode node, java.util.function.Consumer<JsonNode> visitor) {
        if (node.isObject()) {
            visitor.accept(node);
        }
        if (node.isObject() || node.isArray()) {
            node.values().forEach(child -> forEachJsonObject(child, visitor));
        }
    }
}
