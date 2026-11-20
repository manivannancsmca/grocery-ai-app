package com.grocery.delivery.dispatch.location;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * The direct producer of {@code delivery.location} (no outbox: high frequency, best effort). Its own
 * {@code KafkaTemplate<String, byte[]>} built from Spring Boot's producer settings (bootstrap servers, security), with
 * String keys and byte[] values (the Avro wire format), so the application's own producer factory and the dead-letter
 * setup of grocery-common stay untouched. Tuned not to hold up the partner's HTTP call: waits at most
 * {@value #MAX_BLOCK_MS} ms for broker metadata, leader-only acknowledgement, no idempotence, a short delivery timeout.
 * Not a bean of type KafkaTemplate / ProducerFactory on purpose (Boot's single candidates stay single).
 */
@Component
public class KafkaLocationRecordSender implements LocationRecordSender, DisposableBean {

    static final int MAX_BLOCK_MS = 1_000;
    private static final Logger log = LoggerFactory.getLogger(KafkaLocationRecordSender.class);

    private final DefaultKafkaProducerFactory<String, byte[]> producerFactory;
    private final KafkaTemplate<String, byte[]> template;

    public KafkaLocationRecordSender(ProducerFactory<?, ?> applicationProducerFactory) {
        Map<String, Object> config = new HashMap<>(applicationProducerFactory.getConfigurationProperties());
        config.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        Object clientId = config.get(ProducerConfig.CLIENT_ID_CONFIG);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, (clientId != null ? clientId : "delivery-service") + "-location");
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        this.producerFactory = new DefaultKafkaProducerFactory<>(config, new StringSerializer(),
                new ByteArraySerializer());
        this.template = new KafkaTemplate<>(producerFactory);
    }

    @Override
    public void send(ProducerRecord<String, byte[]> record) {
        template.send(record).whenComplete((result, failure) -> {
            if (failure != null) {
                log.warn("DeliveryLocationUpdated for order {} not delivered to Kafka: {}", record.key(),
                        failure.toString());
            }
        });
    }

    @Override
    public void destroy() {
        producerFactory.destroy();
    }
}
