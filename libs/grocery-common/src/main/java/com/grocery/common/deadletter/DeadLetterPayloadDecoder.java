package com.grocery.common.deadletter;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.BeanUtils;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.ClassUtils;

import java.util.Map;

/**
 * Decodes a dead letter's ORIGINAL value bytes exactly as the service's listeners receive them: with the value
 * deserializer of the service's consumer factory (by default grocery-common's
 * {@code ErrorHandlingDeserializer(AvroEventDeserializer)}: Avro → the producer's JSON, legacy JSON unchanged; with
 * {@code grocery.events.avro.enabled=false} Kafka's StringDeserializer). A value the deserializer cannot read
 * (corrupt Avro, unknown schema id, Schema Registry down) raises {@link DecodingException}.
 */
public class DeadLetterPayloadDecoder {

    /** The value could not be decoded; the cause is the deserializer's failure. */
    public static class DecodingException extends RuntimeException {
        DecodingException(Throwable cause) {
            super(cause == null ? "The value cannot be decoded" : cause.getMessage(), cause);
        }
    }

    private final ConsumerFactory<?, ?> consumerFactory;
    private Deserializer<?> deserializer;

    public DeadLetterPayloadDecoder(ConsumerFactory<?, ?> consumerFactory) {
        this.consumerFactory = consumerFactory;
    }

    /**
     * @param topic   the ORIGINAL topic (Avro subjects and schemas are per topic)
     * @param headers the original headers; the deserializer may add to them, as it does for a consumed record
     */
    public synchronized Object decode(String topic, Headers headers, byte[] value) {
        if (value == null) {
            return null;
        }
        try {
            return deserializer().deserialize(topic, headers, value);
        } catch (DecodingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DecodingException(e);
        }
    }

    private Deserializer<?> deserializer() {
        if (deserializer == null) {
            Map<String, Object> config = consumerFactory.getConfigurationProperties();
            Deserializer<?> created = consumerFactory.getValueDeserializer();
            if (created == null) {
                created = instantiate(config.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
            }
            created.configure(config, false);
            if (created instanceof ErrorHandlingDeserializer<?> errorHandling) {
                // Its default turns a failure into a null value + a header; here the failure itself is wanted.
                @SuppressWarnings("unchecked")
                ErrorHandlingDeserializer<Object> typed = (ErrorHandlingDeserializer<Object>) errorHandling;
                typed.setFailedDeserializationFunction(info -> {
                    throw new DecodingException(info.getException());
                });
            }
            deserializer = created;
        }
        return deserializer;
    }

    private static Deserializer<?> instantiate(Object configured) {
        try {
            Class<?> type = configured instanceof Class<?> c ? c
                    : configured instanceof String name ? ClassUtils.forName(name.trim(), DeadLetterPayloadDecoder.class.getClassLoader())
                    : StringDeserializer.class;
            return (Deserializer<?>) BeanUtils.instantiateClass(type);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("Cannot create the value deserializer " + configured, e);
        }
    }
}
