package com.grocery.common.avro;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import tools.jackson.databind.JsonNode;

/**
 * {@link AvroEventEncoder} backed by Confluent's {@link KafkaAvroSerializer}: the schema comes from the classpath
 * ({@link AvroEventSchemas}), the JSON payload is converted strictly ({@link JsonAvroConverter}), and the serializer
 * looks the schema up in the Schema Registry under {@code <topic>-<record full name>} (never registering it unless
 * {@code grocery.events.avro.auto-register-schemas=true}). Schema ids are cached by the shared client, so after the
 * first lookup (or the start-up warm-up) encoding needs no network call. Thread-safe.
 */
public class SchemaRegistryAvroEventEncoder implements AvroEventEncoder {

    private final AvroEventSchemas schemas;
    private final JsonAvroConverter converter;
    private final KafkaAvroSerializer serializer;

    public SchemaRegistryAvroEventEncoder(SchemaRegistryClient client, GroceryAvroProperties properties,
                                          AvroEventSchemas schemas, JsonAvroConverter converter) {
        this.schemas = schemas;
        this.converter = converter;
        this.serializer = new KafkaAvroSerializer(client, SchemaRegistrySupport.serializerConfig(properties));
    }

    @Override
    public byte[] encode(String topic, String eventType, int eventVersion, JsonNode payload) {
        Schema schema = schemas.require(topic, eventType, eventVersion);
        String event = topic + " " + eventType + " v" + eventVersion;
        GenericRecord record;
        try {
            record = converter.toRecord(payload, schema);
        } catch (AvroConversionException e) {
            throw new AvroConversionException("Payload of " + event + " does not match "
                    + AvroEventSchemas.location(topic, eventType, eventVersion), e);
        }
        try {
            return serializer.serialize(topic, record);
        } catch (RuntimeException e) {
            // keeps the cause chain (RestClientException / I/O), so TransientFailures can tell an outage from a
            // schema that is simply not registered
            throw new IllegalStateException("Cannot encode " + event + " with the Schema Registry (subject "
                    + SchemaRegistrySupport.subject(topic, schema.getFullName()) + "): " + e.getMessage(), e);
        }
    }
}
