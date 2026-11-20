package com.grocery.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Dead-letters a record with its ORIGINAL value bytes (Avro wire format or legacy JSON), not the JSON the listener
 * saw, so a later replay of the DLT is byte-identical to the original event:
 * <ul>
 *   <li>value deserialization failed: Spring Kafka already publishes the raw bytes it kept in the
 *       {@code DeserializationException};</li>
 *   <li>the listener failed: the value is taken from the consumer-local header {@value #ORIGINAL_VALUE_HEADER} that
 *       grocery's Avro value deserializer adds to every record.</li>
 * </ul>
 * The helper header is always stripped; the standard {@code kafka_dlt-*} exception and origin headers are kept.
 * Publishes through its own template whose serializers accept {@code byte[]} and {@code String}.
 */
public class OriginalValueDeadLetterPublishingRecoverer extends DeadLetterPublishingRecoverer implements DisposableBean {

    /** Consumer-local header with the original value bytes (added by the Avro value deserializer, never published). */
    public static final String ORIGINAL_VALUE_HEADER = "groceryOriginalValue";

    private final DefaultKafkaProducerFactory<Object, Object> ownedProducerFactory;

    /** Uses the given template; its value serializer must accept {@code byte[]} (and {@code String}). */
    public OriginalValueDeadLetterPublishingRecoverer(KafkaOperations<?, ?> template,
                                                     BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination) {
        super(template, destination);
        this.ownedProducerFactory = null;
    }

    private OriginalValueDeadLetterPublishingRecoverer(DefaultKafkaProducerFactory<Object, Object> producerFactory,
                                                       BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination) {
        super(new KafkaTemplate<>(producerFactory), destination);
        this.ownedProducerFactory = producerFactory;
    }

    /**
     * A recoverer with its own dead-letter producer, configured like the application's producer (bootstrap servers,
     * security, acks, ...) but with {@link #bytesOrStringSerializer()} for keys and values. Closed with the bean.
     */
    public static OriginalValueDeadLetterPublishingRecoverer withOwnProducer(
            Map<String, Object> producerConfig, BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination) {
        return new OriginalValueDeadLetterPublishingRecoverer(deadLetterProducerFactory(producerConfig), destination);
    }

    static DefaultKafkaProducerFactory<Object, Object> deadLetterProducerFactory(Map<String, Object> producerConfig) {
        Map<String, Object> config = new HashMap<>(producerConfig);
        config.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        Object clientId = config.get(ProducerConfig.CLIENT_ID_CONFIG);
        if (clientId != null) {
            config.put(ProducerConfig.CLIENT_ID_CONFIG, clientId + "-dlt");
        }
        return new DefaultKafkaProducerFactory<>(config, bytesOrStringSerializer(), bytesOrStringSerializer());
    }

    /** {@code byte[]} as-is, {@code String} as UTF-8, {@code null} as null. */
    public static Serializer<Object> bytesOrStringSerializer() {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(String.class, new StringSerializer());
        return new DelegatingByTypeSerializer(delegates);
    }

    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
                                                                  TopicPartition topicPartition, Headers headers,
                                                                  byte[] key, byte[] value) {
        Header original = headers.lastHeader(ORIGINAL_VALUE_HEADER);
        headers.remove(ORIGINAL_VALUE_HEADER);
        byte[] originalValue = value != null ? value : original != null ? original.value() : null;
        return super.createProducerRecord(record, topicPartition, headers, key, originalValue);
    }

    @Override
    public void destroy() {
        if (ownedProducerFactory != null) {
            ownedProducerFactory.destroy();
        }
    }
}
