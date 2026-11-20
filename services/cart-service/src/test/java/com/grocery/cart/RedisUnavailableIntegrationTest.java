package com.grocery.cart;

import com.grocery.cart.event.OrderEventsListener;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.common.kafka.TransientFailures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

import static com.grocery.cart.AbstractIntegrationTest.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A second application context whose Redis is unreachable (a closed port): the cart lives nowhere else → 503, and
 * an order event is retried (transient failure) instead of being dead-lettered.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
class RedisUnavailableIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", RedisUnavailableIntegrationTest::closedPort);
        for (String service : List.of("pricing", "inventory", "catalog-query", "vendor", "restaurant", "customer")) {
            registry.add("grocery.cart.services." + service, AbstractIntegrationTest.STUB::baseUrl);
        }
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();          // closed again when this returns: nothing listens there
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    OrderEventsListener orderEvents;
    @Autowired
    JsonMapper json;

    @Test
    void anOrderEventIsRetriedUntilRedisIsBack() {
        ConsumerRecord<String, String> event = OrderEventRecords.orderCreated(json, OrderEventRecords.newEventId(),
                UUID.randomUUID(), "2026-11-11T10:15:30.120Z");
        // A transient failure: grocery-common's error handler keeps retrying the record (never the DLT)
        assertThatThrownBy(() -> orderEvents.on(event))
                .isInstanceOfSatisfying(TransientEventProcessingException.class,
                        e -> assertThat(TransientFailures.isTransient(e)).isTrue());
    }

    @Test
    void everyCartRequestAnswers503CartUnavailable() throws Exception {
        UUID customerId = UUID.randomUUID();
        mvc.perform(get("/api/cart").with(customer(customerId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CART_UNAVAILABLE"));
        mvc.perform(post("/api/cart/items").with(customer(customerId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PRODUCT\",\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CART_UNAVAILABLE"));
        mvc.perform(put("/api/cart/items/{id}", UUID.randomUUID()).with(customer(customerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CART_UNAVAILABLE"));
        mvc.perform(delete("/api/cart/items/{id}", UUID.randomUUID()).with(customer(customerId)))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(delete("/api/cart").with(customer(customerId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CART_UNAVAILABLE"));
        mvc.perform(get("/api/cart/preview").with(customer(customerId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CART_UNAVAILABLE"));
    }
}
