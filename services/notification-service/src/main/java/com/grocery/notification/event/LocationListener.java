package com.grocery.notification.event;

import com.grocery.notification.service.LocationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Phase 9: the second consumer of this service — group {@value LocationService#CONSUMER} on {@code delivery.location}
 * (the partners' live positions, high frequency, best effort). It is its own listener container with its own consumer
 * threads, so location traffic never delays the domain events of {@link DispatchListener}. A new group starts at the
 * latest offset (old positions are worthless). A position that fails to be handled (the database or Redis down, …)
 * is logged and dropped, never retried or dead-lettered: the next one follows within seconds.
 */
@Component
public class LocationListener {

    private static final Logger log = LoggerFactory.getLogger(LocationListener.class);

    private final LocationService locations;

    public LocationListener(LocationService locations) {
        this.locations = locations;
    }

    @KafkaListener(id = "notification-location", groupId = LocationService.CONSUMER, topics = "delivery.location",
            properties = "auto.offset.reset=latest")
    public void on(ConsumerRecord<String, String> record) {
        try {
            locations.handle(record);
        } catch (RuntimeException e) {
            log.warn("Location {}-{}@{} dropped: {}", record.topic(), record.partition(), record.offset(),
                    e.toString());
        }
    }
}
