package com.grocery.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Notification service (Phase 7, docs/api/phase-7-real-time-updates.md): consumes the eight domain topics in one
 * consumer group ({@code notification-service.dispatch}), keeps its own projections (store owners, orders), resolves
 * recipients on the server, stores persistent notifications in MySQL and, after commit, publishes every notification,
 * signal and session message on the Redis channel {@code grocery:realtime}. Every replica subscribes to that channel
 * and writes the message to its locally connected Server-Sent-Event streams whose audiences match. It publishes no
 * Kafka events.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
