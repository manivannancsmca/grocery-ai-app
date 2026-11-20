package com.grocery.common.outbox;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventTest {

    @Test
    void factoryCreatesVersionOneEvents() {
        OutboxEvent event = OutboxEvent.of("order.events", "Order", "o-1", "OrderCreated", Map.of("total", 10));
        assertThat(event.eventVersion()).isEqualTo(1);
    }

    @Test
    void rejectsBlankRoutingFields() {
        assertThatThrownBy(() -> OutboxEvent.of(" ", "Order", "o-1", "OrderCreated", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("topic");
        assertThatThrownBy(() -> new OutboxEvent("order.events", "Order", "o-1", "OrderCreated", 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventVersion");
    }
}
