package com.grocery.common.avro;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.OriginalValueDeadLetterPublishingRecoverer;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.common.kafka.TransientFailures;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericContainer;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Kafka value deserializer that hands every listener the same JSON {@code String} it received before Avro:
 * <ul>
 *   <li>{@code null} -&gt; {@code null};</li>
 *   <li>Confluent wire format (magic byte 0, length &gt;= 5) -&gt; the writer schema from the Schema Registry,
 *       Confluent's {@link KafkaAvroDeserializer} (generic record, writer schema, no specific reader, trailing bytes
 *       rejected) -&gt; {@link JsonAvroConverter} -&gt; JSON text;</li>
 *   <li>anything else -&gt; the bytes as a UTF-8 string, unchanged (JSON events published before the switch, and
 *       replays of them).</li>
 * </ul>
 * Failures: a corrupt Avro value or a schema id the registry does not know (404, other 4xx) is an
 * {@link InvalidEventException} (poison: dead-lettered at once); an unreachable registry, a timeout, 5xx, 408 or 429
 * is a {@link TransientEventProcessingException} (the error handler retries the same record until the registry is
 * back). Installed wrapped in Spring Kafka's {@code ErrorHandlingDeserializer}, so a bad record never kills the
 * consumer.
 *
 * <p>For dead-letter fidelity the original value bytes are put in the consumer-local header
 * {@link #ORIGINAL_VALUE_HEADER} (no copy: Kafka hands over a fresh array per record); the dead-letter recoverer
 * publishes them and strips the header. Writer schemas are cached per id; after a registry outage, records with a
 * not-yet-cached id fail fast for {@value #FAIL_FAST_MILLIS} ms, so one poll never waits for many timeouts.
 */
public class AvroEventDeserializer implements Deserializer<String> {

    /** Consumer-local header with the record's original value bytes; never published (see the class comment). */
    public static final String ORIGINAL_VALUE_HEADER = OriginalValueDeadLetterPublishingRecoverer.ORIGINAL_VALUE_HEADER;

    static final long FAIL_FAST_MILLIS = 500;
    private static final byte MAGIC_BYTE = 0;
    private static final int HEADER_LENGTH = 5;

    private final SchemaRegistryClient client;
    private final KafkaAvroDeserializer avroDeserializer;
    private final JsonAvroConverter converter;
    private final ConcurrentMap<Integer, Schema> writerSchemas = new ConcurrentHashMap<>();
    private volatile long registryFailedAtNanos;
    private volatile boolean registryFailed;

    public AvroEventDeserializer(SchemaRegistryClient client, GroceryAvroProperties properties,
                                 JsonAvroConverter converter) {
        this.client = client;
        this.converter = converter;
        this.avroDeserializer = new KafkaAvroDeserializer(client, SchemaRegistrySupport.deserializerConfig(properties));
    }

    /** Whether the bytes are in the Confluent wire format (JSON text never starts with a 0 byte). */
    public static boolean isAvro(byte[] data) {
        return data != null && data.length >= HEADER_LENGTH && data[0] == MAGIC_BYTE;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // fully configured by the constructor (shared client, fixed conventions)
    }

    @Override
    public String deserialize(String topic, byte[] data) {
        return deserialize(topic, null, data);
    }

    @Override
    public String deserialize(String topic, Headers headers, byte[] data) {
        if (data == null) {
            return null;
        }
        if (headers != null) {
            headers.remove(ORIGINAL_VALUE_HEADER);
            headers.add(ORIGINAL_VALUE_HEADER, data);
        }
        return isAvro(data) ? decode(topic, headers, data) : new String(data, StandardCharsets.UTF_8);
    }

    private String decode(String topic, Headers headers, byte[] data) {
        int schemaId = ByteBuffer.wrap(data, 1, 4).getInt();
        Schema writerSchema = writerSchema(topic, schemaId);
        Object value;
        try {
            value = avroDeserializer.deserialize(topic, headers, data);   // the writer schema is cached by now
        } catch (RuntimeException e) {
            throw decodingFailure(topic, schemaId, e);
        }
        try {
            return converter.toJsonString(value, value instanceof GenericContainer container
                    ? container.getSchema() : writerSchema);
        } catch (RuntimeException e) {
            throw new InvalidEventException("Avro value on " + topic + " (schema id " + schemaId
                    + ") cannot be rendered as JSON: " + e.getMessage(), serializable(e));
        }
    }

    /** Fetches (once) the writer schema, telling an unknown schema (poison) from an unavailable registry (outage). */
    private Schema writerSchema(String topic, int schemaId) {
        Schema known = writerSchemas.get(schemaId);
        if (known != null) {
            return known;
        }
        if (registryFailed && System.nanoTime() - registryFailedAtNanos < TimeUnit.MILLISECONDS.toNanos(FAIL_FAST_MILLIS)) {
            throw new TransientEventProcessingException("Schema Registry unavailable (failed less than "
                    + FAIL_FAST_MILLIS + " ms ago); schema id " + schemaId + " of " + topic + " is not cached yet");
        }
        ParsedSchema parsed;
        try {
            parsed = client.getSchemaBySubjectAndId(null, schemaId);   // the lookup (and cache entry) the deserializer uses
        } catch (RestClientException e) {
            if (TransientFailures.isTransientRegistryStatus(e.getStatus())) {
                throw registryUnavailable(topic, schemaId, e);
            }
            throw new InvalidEventException("Schema id " + schemaId + " of a record on " + topic
                    + " is not known to the Schema Registry (HTTP " + e.getStatus() + ", error " + e.getErrorCode()
                    + "): " + e.getMessage(), serializable(e));
        } catch (IOException | RuntimeException e) {
            throw registryUnavailable(topic, schemaId, e);
        }
        registryFailed = false;
        if (!(parsed instanceof AvroSchema avroSchema)) {
            throw new InvalidEventException("Schema id " + schemaId + " of a record on " + topic + " is a "
                    + (parsed == null ? "missing" : parsed.schemaType()) + " schema, not Avro");
        }
        Schema schema = avroSchema.rawSchema();
        writerSchemas.putIfAbsent(schemaId, schema);
        return schema;
    }

    private TransientEventProcessingException registryUnavailable(String topic, int schemaId, Exception e) {
        registryFailedAtNanos = System.nanoTime();
        registryFailed = true;
        return new TransientEventProcessingException("Schema Registry unavailable while resolving schema id "
                + schemaId + " of a record on " + topic + ": " + e, serializable(e));
    }

    /** After the schema was fetched only a registry hiccup (cache miss) can be transient; the rest is corrupt data. */
    private RuntimeException decodingFailure(String topic, int schemaId, RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof RestClientException rest) {
                if (TransientFailures.isTransientRegistryStatus(rest.getStatus())) {
                    return registryUnavailable(topic, schemaId, e);
                }
                break;
            }
            if (cause instanceof RetriableException || isNetworkFailure(cause)) {
                return registryUnavailable(topic, schemaId, e);
            }
        }
        return new InvalidEventException("Corrupt Avro value on " + topic + " (schema id " + schemaId + "): "
                + e.getMessage(), serializable(e));
    }

    private static boolean isNetworkFailure(Throwable failure) {
        String name = failure.getClass().getName();
        return failure instanceof InterruptedIOException || name.startsWith("java.net.") || name.startsWith("javax.net.");
    }

    /**
     * ErrorHandlingDeserializer Java-serializes the failure into a record header; a cause that cannot be serialized
     * would replace it with a plain RuntimeException and lose the poison/outage classification, so such a cause is
     * replaced by a serializable copy (same message and stack trace) here.
     */
    static Throwable serializable(Throwable cause) {
        try (ObjectOutputStream out = new ObjectOutputStream(OutputStream.nullOutputStream())) {
            out.writeObject(cause);
            return cause;
        } catch (IOException | RuntimeException e) {
            RuntimeException copy = new RuntimeException(cause.toString());
            copy.setStackTrace(cause.getStackTrace());
            return copy;
        }
    }

    @Override
    public void close() {
        // the Schema Registry client is shared and closed by its Spring bean
    }
}
