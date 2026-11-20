package com.grocery.events.schemas;

import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the gate ({@link CompatibilityGate}) catches breaking changes and lets allowed ones through, by editing a
 * real released schema (order.events/OrderCreated v1) the way a developer would.
 */
class CompatibilityGateRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static EventSchemaFile orderCreated() {
        return EventSchemaFile.all().stream().filter(f -> f.id().equals("order.events/OrderCreated")).findFirst()
                .orElseThrow();
    }

    /** The released v1 of OrderCreated, as kept in the baseline. */
    private static List<AvroSchema> released() {
        return CompatibilityGate.schemas(CompatibilityGate.baselines(orderCreated()));
    }

    /** OrderCreated with {@code edit} applied to its JSON. */
    private static AvroSchema edited(Consumer<ObjectNode> edit) {
        ObjectNode root = (ObjectNode) JSON.readTree(orderCreated().text());
        edit.accept(root);
        return new AvroSchema(JSON.writeValueAsString(root));
    }

    private static List<String> gate(Consumer<ObjectNode> edit) {
        return CompatibilityGate.check(edited(edit), released());
    }

    private static ArrayNode fields(ObjectNode record) {
        return (ArrayNode) record.get("fields");
    }

    private static ObjectNode field(ObjectNode record, String name) {
        for (JsonNode field : fields(record)) {
            if (name.equals(field.get("name").stringValue())) {
                return (ObjectNode) field;
            }
        }
        throw new IllegalArgumentException("no field " + name);
    }

    private static void removeField(ObjectNode record, String name) {
        ArrayNode fields = fields(record);
        for (int i = 0; i < fields.size(); i++) {
            if (name.equals(fields.get(i).get("name").stringValue())) {
                fields.remove(i);
                return;
            }
        }
        throw new IllegalArgumentException("no field " + name);
    }

    private static void addField(ObjectNode record, String fieldJson) {
        fields(record).add(JSON.readTree(fieldJson));
    }

    /** The {@code OrderCreatedItem} record inside {@code items}. */
    private static ObjectNode itemRecord(ObjectNode root) {
        return (ObjectNode) field(root, "items").get("type").get("items");
    }

    @Test
    void theUnchangedSchemaPasses() {
        assertThat(gate(root -> {
        })).isEmpty();
    }

    @Nested
    class BreakingChangesAreRejected {

        @Test
        void addingAFieldWithoutADefault() {
            assertThat(gate(root -> addField(root,
                    "{\"name\":\"channel\",\"type\":\"string\",\"doc\":\"Sales channel.\"}"))).isNotEmpty();
        }

        @Test
        void changingAFieldsType() {
            assertThat(gate(root -> field(root, "orderNumber").put("type", "long"))).isNotEmpty();
        }

        @Test
        void changingANestedFieldsType() {
            assertThat(gate(root -> field(itemRecord(root), "quantity").put("type", "string"))).isNotEmpty();
        }

        @Test
        void renamingAFieldWithoutAnAlias() {
            assertThat(gate(root -> field(root, "orderNumber").put("name", "orderNo"))).isNotEmpty();
        }

        @Test
        void makingANullableFieldRequired() {
            assertThat(gate(root -> {
                ObjectNode storeName = field(root, "storeName");
                storeName.put("type", "string");
                storeName.remove("default");
            })).isNotEmpty();
        }

        @Test
        void renamingANestedRecord() {
            assertThat(gate(root -> itemRecord(root).put("name", "OrderCreatedLine"))).isNotEmpty();
        }

        @Test
        void changingAStringIntoAnAvroEnum() {
            assertThat(gate(root -> field(root, "orderType").set("type", JSON.readTree(
                    "{\"type\":\"enum\",\"name\":\"OrderCreatedType\",\"symbols\":[\"GROCERY\"]}")))).isNotEmpty();
        }
    }

    @Nested
    class AllowedChangesPass {

        @Test
        void addingANullableFieldWithDefaultNull() {
            assertThat(gate(root -> addField(root, "{\"name\":\"giftMessage\",\"type\":[\"null\",\"string\"],"
                    + "\"default\":null,\"doc\":\"Gift message; null if none.\"}"))).isEmpty();
        }

        @Test
        void addingANestedNullableFieldWithDefaultNull() {
            assertThat(gate(root -> addField(itemRecord(root), "{\"name\":\"unitLabel\",\"type\":[\"null\","
                    + "\"string\"],\"default\":null,\"doc\":\"Pack size label.\"}"))).isEmpty();
        }

        @Test
        void removingAField() {
            assertThat(gate(root -> removeField(root, "storeName"))).isEmpty();
        }

        @Test
        void renamingAFieldWithAnAlias() {
            assertThat(gate(root -> {
                ObjectNode field = field(root, "orderNumber");
                field.put("name", "orderNo");
                field.putArray("aliases").add("orderNumber");
            })).isEmpty();
        }

        @Test
        void makingARequiredFieldNullable() {
            assertThat(gate(root -> {
                ObjectNode orderNumber = field(root, "orderNumber");
                orderNumber.set("type", JSON.readTree("[\"null\",\"string\"]"));
                orderNumber.putNull("default");
            })).isEmpty();
        }

        @Test
        void wideningIntToLong() {
            assertThat(gate(root -> field(itemRecord(root), "quantity").put("type", "long"))).isEmpty();
        }

        @Test
        void editingDocs() {
            assertThat(gate(root -> root.put("doc", "Reworded."))).isEmpty();
        }
    }

    /** BACKWARD would only compare with the latest version; BACKWARD_TRANSITIVE compares with all of them. */
    @Test
    void theGateIsTransitive() {
        AvroSchema v1 = new AvroSchema("""
                {"type":"record","name":"Sample","namespace":"com.grocery.events.test","fields":[
                  {"name":"id","type":"string"},
                  {"name":"code","type":"string"}]}""");
        AvroSchema v2 = new AvroSchema("""
                {"type":"record","name":"Sample","namespace":"com.grocery.events.test","fields":[
                  {"name":"id","type":"string"}]}""");
        AvroSchema v3 = new AvroSchema("""
                {"type":"record","name":"Sample","namespace":"com.grocery.events.test","fields":[
                  {"name":"id","type":"string"},
                  {"name":"code","type":"int","default":0}]}""");

        assertThat(CompatibilityGate.check(v2, List.of(v1))).isEmpty();
        assertThat(CompatibilityGate.check(CompatibilityLevel.BACKWARD, v3, List.of(v1, v2)))
                .as("v3 reads v2 data").isEmpty();
        assertThat(CompatibilityGate.check(v3, List.of(v1, v2)))
                .as("v3 cannot read v1 data: code was a string").isNotEmpty();
    }
}
