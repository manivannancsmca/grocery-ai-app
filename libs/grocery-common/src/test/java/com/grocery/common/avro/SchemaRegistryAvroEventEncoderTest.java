package com.grocery.common.avro;

import com.grocery.common.kafka.TransientFailures;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static com.grocery.common.avro.TestEvents.JSON;
import static com.grocery.common.avro.TestEvents.PAYMENT_CAPTURED;
import static com.grocery.common.avro.TestEvents.SUBJECT_V1;
import static com.grocery.common.avro.TestEvents.SUBJECT_V2;
import static com.grocery.common.avro.TestEvents.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRegistryAvroEventEncoderTest {

    private static final String SCOPE = "encoder-test";

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final AvroEventSchemas schemas = new AvroEventSchemas();

    @AfterEach
    void dropScope() {
        MockSchemaRegistry.dropScope(SCOPE);
    }

    private SchemaRegistryAvroEventEncoder encoder(GroceryAvroProperties properties) {
        return new SchemaRegistryAvroEventEncoder(SchemaRegistrySupport.newClient(properties), properties, schemas, converter);
    }

    private JsonNode payload(Object payload) {
        return converter.readExact(JSON.writeValueAsString(payload));
    }

    private static GenericRecord decode(byte[] wire, Schema schema) throws IOException {
        return new GenericDatumReader<GenericRecord>(schema)
                .read(null, DecoderFactory.get().binaryDecoder(wire, 5, wire.length - 5, null));
    }

    @Test
    void encodesInTheConfluentWireFormatUnderTheTopicRecordSubject() throws Exception {
        GroceryAvroProperties properties = TestEvents.properties("mock://" + SCOPE, true);
        SchemaRegistryClient client = SchemaRegistrySupport.newClient(properties);
        TestEvents.PaymentCaptured event = TestEvents.paymentCaptured();
        JsonNode json = payload(event);

        byte[] wire = encoder(properties).encode(TOPIC, PAYMENT_CAPTURED, 1, json);

        Schema schema = schemas.require(TOPIC, PAYMENT_CAPTURED, 1);
        assertThat(wire[0]).isZero();                                                  // magic byte
        int id = ByteBuffer.wrap(wire, 1, 4).getInt();
        assertThat(client.getAllSubjects()).containsExactly(SUBJECT_V1);
        assertThat(client.getId(SUBJECT_V1, new AvroSchema(schema))).isEqualTo(id);
        assertThat(converter.readExact(converter.toJsonString(decode(wire, schema), schema))).isEqualTo(json);
    }

    @Test
    void eventVersionTwoUsesTheV2Schema() throws Exception {
        GroceryAvroProperties properties = TestEvents.properties("mock://" + SCOPE, true);
        JsonNode v2 = converter.readExact("""
                {"orderId":"0b0e5c3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","amountMinor":24500,"capturedAt":"2026-11-11T10:05:00.250Z"}""");

        byte[] wire = encoder(properties).encode(TOPIC, PAYMENT_CAPTURED, 2, v2);

        Schema schema = schemas.require(TOPIC, PAYMENT_CAPTURED, 2);
        assertThat(schema.getFullName()).isEqualTo("com.grocery.events.test.PaymentCapturedV2");
        assertThat(SchemaRegistrySupport.newClient(properties).getAllSubjects()).containsExactly(SUBJECT_V2);
        assertThat(converter.readExact(converter.toJsonString(decode(wire, schema), schema))).isEqualTo(v2);
        // a v1 payload does not fit the v2 schema
        assertThatThrownBy(() -> encoder(properties).encode(TOPIC, PAYMENT_CAPTURED, 2, payload(TestEvents.paymentCaptured())))
                .isInstanceOf(AvroConversionException.class)
                .hasMessageStartingWith("Payload of test.events PaymentCaptured v2 does not match avro/test.events/PaymentCaptured.v2.avsc: PaymentCapturedV2.");
    }

    @Test
    void anEventWithoutSchemaOnTheClasspathIsNeverPublished() {
        SchemaRegistryAvroEventEncoder encoder = encoder(TestEvents.properties("mock://" + SCOPE, true));
        JsonNode json = payload(TestEvents.paymentCaptured());

        assertThatThrownBy(() -> encoder.encode(TOPIC, PAYMENT_CAPTURED, 3, json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Avro schema for test.events PaymentCaptured v3")
                .hasMessageContaining("avro/test.events/PaymentCaptured.v3.avsc");
        assertThatThrownBy(() -> encoder.encode("order.events", "OrderCreated", 1, json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("avro/order.events/OrderCreated.avsc");
        assertThatThrownBy(() -> encoder.encode("../test.events", PAYMENT_CAPTURED, 1, json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payloadsThatDoNotMatchTheSchemaFailWithEventAndFieldPath() {
        SchemaRegistryAvroEventEncoder encoder = encoder(TestEvents.properties("mock://" + SCOPE, true));
        JsonNode json = payload(TestEvents.paymentCaptured());
        ((tools.jackson.databind.node.ObjectNode) json.get("items").get(1)).put("unitPrice", 1.005);

        assertThatThrownBy(() -> encoder.encode(TOPIC, PAYMENT_CAPTURED, 1, json))
                .isInstanceOf(AvroConversionException.class)
                .hasMessage("Payload of test.events PaymentCaptured v1 does not match avro/test.events/PaymentCaptured.avsc: "
                        + "PaymentCaptured.items[1].unitPrice: 1.005 cannot be stored as decimal(10,2) without rounding")
                .satisfies(e -> assertThat(((AvroConversionException) e).path()).isEqualTo("items[1].unitPrice"));
    }

    @Test
    void withoutAutoRegistrationOnlySchemasRegisteredByThePipelineAreUsed() throws Exception {
        GroceryAvroProperties properties = TestEvents.properties("mock://" + SCOPE, false);
        SchemaRegistryAvroEventEncoder encoder = encoder(properties);
        JsonNode json = payload(TestEvents.paymentCapturedWithNulls());

        assertThatThrownBy(() -> encoder.encode(TOPIC, PAYMENT_CAPTURED, 1, json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subject " + SUBJECT_V1)
                .hasRootCauseInstanceOf(RestClientException.class)
                .satisfies(e -> assertThat(TransientFailures.isTransient(e)).isFalse());

        SchemaRegistryClient pipeline = SchemaRegistrySupport.newClient(properties);
        int id = pipeline.register(SUBJECT_V1, new AvroSchema(schemas.require(TOPIC, PAYMENT_CAPTURED, 1)));
        byte[] wire = encoder.encode(TOPIC, PAYMENT_CAPTURED, 1, json);
        assertThat(ByteBuffer.wrap(wire, 1, 4).getInt()).isEqualTo(id);
        assertThat(pipeline.getAllSubjects()).containsExactly(SUBJECT_V1);              // nothing registered by the encoder
    }

    @Test
    void anUnreachableRegistryFailsTheTransactionAsAnOutage() {
        SchemaRegistryAvroEventEncoder encoder = encoder(TestEvents.properties("http://127.0.0.1:1", false));

        assertThatThrownBy(() -> encoder.encode(TOPIC, PAYMENT_CAPTURED, 1, payload(TestEvents.paymentCaptured())))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(TransientFailures.isTransient(e)).isTrue());
    }

    @Test
    void schemasAreReadOnceAndListedWithTheirSubjects() {
        assertThat(schemas.require(TOPIC, PAYMENT_CAPTURED, 1)).isSameAs(schemas.require(TOPIC, PAYMENT_CAPTURED, 1));
        assertThat(AvroEventSchemas.location(TOPIC, PAYMENT_CAPTURED, 1)).isEqualTo("avro/test.events/PaymentCaptured.avsc");
        assertThat(AvroEventSchemas.location(TOPIC, PAYMENT_CAPTURED, 7)).isEqualTo("avro/test.events/PaymentCaptured.v7.avsc");

        List<AvroEventSchemas.EventSchema> all = new AvroEventSchemas().all();
        assertThat(all).extracting(AvroEventSchemas.EventSchema::subject).containsExactlyInAnyOrder(SUBJECT_V1, SUBJECT_V2);
        assertThat(all).extracting(AvroEventSchemas.EventSchema::eventVersion).containsExactlyInAnyOrder(1, 2);
        assertThat(all).allSatisfy(s -> assertThat(s.topic()).isEqualTo(TOPIC));
    }

    @Test
    void warmUpResolvesEveryRegisteredSchemaAndNeverThrows() throws Exception {
        GroceryAvroProperties properties = TestEvents.properties("mock://" + SCOPE, false);
        SchemaRegistryClient client = SchemaRegistrySupport.newClient(properties);
        client.register(SUBJECT_V1, new AvroSchema(schemas.require(TOPIC, PAYMENT_CAPTURED, 1)));

        assertThat(new AvroSchemaWarmUp(client, schemas, false).warmUp())
                .isEqualTo(new AvroSchemaWarmUp.Result(2, 1, 1, false));                  // v2 is not registered
        assertThat(new AvroSchemaWarmUp(client, schemas, true).warmUp())
                .isEqualTo(new AvroSchemaWarmUp.Result(2, 2, 0, false));                  // dev/test: registers
        assertThat(client.getAllSubjects()).containsExactlyInAnyOrder(SUBJECT_V1, SUBJECT_V2);

        SchemaRegistryClient down = SchemaRegistrySupport.newClient(TestEvents.properties("http://127.0.0.1:1", false));
        assertThat(new AvroSchemaWarmUp(down, schemas, false).warmUp())
                .isEqualTo(new AvroSchemaWarmUp.Result(2, 0, 0, true));
    }
}
