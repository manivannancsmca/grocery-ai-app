package com.grocery.common.deadletter;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Writes dead-letter states to the compacted topic {@code _grocery.dead-letter-actions} through its own small producer
 * (configured like the application's: bootstrap servers, security; String key, JSON bytes value; acks=all; short
 * timeouts so an unavailable broker fails the request within seconds instead of blocking it for a minute).
 */
public class KafkaDeadLetterActionLog implements DeadLetterActionLog, DisposableBean {

    private static final long SEND_TIMEOUT_SECONDS = 15;

    private final String topic;
    private final Map<String, Object> config;
    private volatile KafkaProducer<String, byte[]> producer;

    /** @param baseConfig the application's producer configuration */
    public KafkaDeadLetterActionLog(Map<String, Object> baseConfig, String topic) {
        this.topic = topic;
        Map<String, Object> config = new HashMap<>(baseConfig);
        config.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        Object clientId = config.get(ProducerConfig.CLIENT_ID_CONFIG);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, (clientId == null ? "grocery" : clientId) + "-dead-letter-actions");
        this.config = config;
    }

    @Override
    public long append(String key, DeadLetterAction action) {
        try {
            return producer().send(new ProducerRecord<>(topic, key, action.toJson()))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS).offset();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while writing to " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot write to " + topic + ": " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Cannot write to " + topic + ": no answer within " + SEND_TIMEOUT_SECONDS + " s", e);
        }
    }

    private KafkaProducer<String, byte[]> producer() {
        KafkaProducer<String, byte[]> current = producer;
        if (current == null) {
            synchronized (this) {
                current = producer;
                if (current == null) {
                    current = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
                    producer = current;
                }
            }
        }
        return current;
    }

    @Override
    public synchronized void destroy() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(2));
            producer = null;
        }
    }
}
