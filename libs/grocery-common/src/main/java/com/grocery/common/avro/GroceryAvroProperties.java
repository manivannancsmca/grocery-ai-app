package com.grocery.common.avro;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Avro on the wire for domain events ({@code grocery.events.avro.*}).
 *
 * <pre>
 * grocery.events.avro.enabled=true                                # false = rollback switch: JSON only, as before 0.6.0
 * grocery.events.avro.schema-registry-url=http://localhost:8081   # env GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL
 * grocery.events.avro.auto-register-schemas=false                 # true only in tests / local experiments
 * grocery.events.avro.client.basic.auth.credentials.source=USER_INFO
 * grocery.events.avro.client.basic.auth.user.info=${SR_USER}:${SR_PASSWORD}
 * </pre>
 */
@ConfigurationProperties(prefix = "grocery.events.avro")
public class GroceryAvroProperties {

    /**
     * Avro on the wire. When false (the rollback switch) everything behaves exactly as before 0.6.0: the outbox writes
     * only the JSON {@code payload} column and consumers read values with Kafka's StringDeserializer.
     */
    private boolean enabled = true;

    /** Confluent Schema Registry base URL(s), comma-separated; {@code mock://<scope>} in tests. */
    private String schemaRegistryUrl = "http://localhost:8081";

    /**
     * Let the serializer register missing schemas. Production keeps false: schemas are registered (and checked for
     * BACKWARD_TRANSITIVE compatibility) by the delivery pipeline, never by a service.
     */
    private boolean autoRegisterSchemas = false;

    /**
     * Extra settings for the Confluent Schema Registry client, passed as-is, e.g.
     * {@code basic.auth.credentials.source}, {@code basic.auth.user.info}, {@code bearer.auth.*},
     * {@code schema.registry.ssl.truststore.location}, {@code http.connect.timeout.ms}, {@code max.retries}.
     */
    private Map<String, String> client = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSchemaRegistryUrl() {
        return schemaRegistryUrl;
    }

    public void setSchemaRegistryUrl(String schemaRegistryUrl) {
        this.schemaRegistryUrl = schemaRegistryUrl;
    }

    public boolean isAutoRegisterSchemas() {
        return autoRegisterSchemas;
    }

    public void setAutoRegisterSchemas(boolean autoRegisterSchemas) {
        this.autoRegisterSchemas = autoRegisterSchemas;
    }

    public Map<String, String> getClient() {
        return client;
    }

    public void setClient(Map<String, String> client) {
        this.client = client;
    }
}
