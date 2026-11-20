package com.grocery.events.contract.fixture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Listener shapes the contract helper must understand (this module's own tests; not shipped in the test-jar): a
 * switch over constants, a prefix + suffix dispatch, a dispatch on a type without schema, a topic without schemas and
 * a listener that throws.
 */
public final class FixtureListeners {

    private FixtureListeners() {
    }

    static String eventType(ConsumerRecord<String, String> record) {
        return new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8);
    }

    /** order.events: OrderCreated and OrderCancelled via constants in a switch, the rest ignored. */
    public static class OrderListener {

        static final String ORDER_CREATED = "OrderCreated";
        public final List<String> received = new ArrayList<>();

        @KafkaListener(id = "fixture-order", groupId = "fixture.order", topics = "order.events")
        public void on(ConsumerRecord<String, String> record) {
            switch (eventType(record)) {
                case ORDER_CREATED, "OrderCancelled" -> {
                    if (!record.value().contains("orderId")) {
                        throw new IllegalStateException("no orderId in " + record.value());
                    }
                    received.add(eventType(record));
                }
                default -> {
                    // ignored
                }
            }
        }
    }

    /** vendor.events + restaurant.events: "<Store>" prefix + lifecycle suffix, like identity-service. */
    public static class LifecycleListener {

        @KafkaListener(topics = {"vendor.events", "restaurant.events"})
        public void on(ConsumerRecord<String, String> record) {
            String type = eventType(record);
            String transition = type.startsWith("Vendor") ? type.substring("Vendor".length())
                    : type.startsWith("Restaurant") ? type.substring("Restaurant".length()) : "";
            switch (transition) {
                case "Approved", "Suspended" -> {
                }
                default -> {
                }
            }
        }
    }

    /** Dispatches on a type that has no schema. */
    public static class UnknownTypeListener {

        @KafkaListener(topics = "payment.events")
        public void on(ConsumerRecord<String, String> record) {
            if ("PaymentRefundedTwice".equals(eventType(record)) || "PaymentCaptured".equals(eventType(record))) {
                return;
            }
        }
    }

    /** Subscribes to a topic without schemas. */
    public static class UnknownTopicListener {

        @KafkaListener(topics = {"loyalty.events"})
        public void on(ConsumerRecord<String, String> record) {
        }
    }

    /** Throws for one event type, like a consumer whose DTO does not fit the schema. */
    public static class FailingListener {

        @KafkaListener(topics = "inventory.events")
        public void on(ConsumerRecord<String, String> record) {
            if ("StockReserved".equals(eventType(record))) {
                throw new IllegalArgumentException("cannot map items");
            }
        }
    }
}
