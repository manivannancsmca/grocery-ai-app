package com.grocery.common.avro;

import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientFactory;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the one Schema Registry client a service shares between its encoder and its consumers, and the serializer /
 * deserializer settings every grocery service must use (fixed conventions, not configurable):
 * <ul>
 *   <li>subject = {@code <topic>-<record full name>} ({@link TopicRecordNameStrategy});</li>
 *   <li>never {@code use.latest.version}: the writer schema is always the one on the producer's classpath;</li>
 *   <li>generic records with raw Avro values (no logical-type conversions): {@link JsonAvroConverter} owns the
 *       JSON &lt;-&gt; Avro mapping;</li>
 *   <li>Confluent wire format: magic byte 0, 4-byte schema id, Avro binary.</li>
 * </ul>
 */
public final class SchemaRegistrySupport {

    /** Schemas cached per subject by the client (Confluent's default). */
    static final int CACHE_CAPACITY = 1000;

    /**
     * Shorter than Confluent's 60 s defaults: a lookup may run inside a business transaction (cold cache) or in a
     * consumer's poll loop. Override with {@code grocery.events.avro.client.http.*}.
     */
    static final Map<String, String> CLIENT_DEFAULTS = Map.of(
            "http.connect.timeout.ms", "5000",
            "http.read.timeout.ms", "10000");

    private SchemaRegistrySupport() {
    }

    public static SchemaRegistryClient newClient(GroceryAvroProperties properties) {
        return SchemaRegistryClientFactory.newClient(urls(properties), CACHE_CAPACITY,
                List.of(new AvroSchemaProvider()), clientConfig(properties), Map.of());
    }

    static List<String> urls(GroceryAvroProperties properties) {
        List<String> urls = Arrays.stream(properties.getSchemaRegistryUrl().split(","))
                .map(String::trim).filter(url -> !url.isEmpty()).toList();
        if (urls.isEmpty()) {
            throw new IllegalStateException("grocery.events.avro.schema-registry-url must not be empty");
        }
        return urls;
    }

    static Map<String, Object> clientConfig(GroceryAvroProperties properties) {
        Map<String, Object> config = new HashMap<>(CLIENT_DEFAULTS);
        config.putAll(properties.getClient());
        return config;
    }

    /** KafkaAvroSerializer settings (the client itself is passed in, so no connection settings are needed here). */
    static Map<String, Object> serializerConfig(GroceryAvroProperties properties) {
        Map<String, Object> config = commonSerdeConfig(properties);
        config.put("auto.register.schemas", properties.isAutoRegisterSchemas());
        config.put("use.latest.version", false);
        config.put("normalize.schemas", false);
        return config;
    }

    /** KafkaAvroDeserializer settings: generic records read with the writer schema, trailing bytes rejected. */
    static Map<String, Object> deserializerConfig(GroceryAvroProperties properties) {
        Map<String, Object> config = commonSerdeConfig(properties);
        config.put("specific.avro.reader", false);
        config.put("avro.fail.on.trailing.data", true);
        return config;
    }

    private static Map<String, Object> commonSerdeConfig(GroceryAvroProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url", String.join(",", urls(properties)));
        config.put("value.subject.name.strategy", TopicRecordNameStrategy.class.getName());
        config.put("avro.use.logical.type.converters", false);
        return config;
    }

    /** {@code <topic>-<record full name>}, as {@link TopicRecordNameStrategy} names it. */
    public static String subject(String topic, String recordFullName) {
        return topic + "-" + recordFullName;
    }
}
