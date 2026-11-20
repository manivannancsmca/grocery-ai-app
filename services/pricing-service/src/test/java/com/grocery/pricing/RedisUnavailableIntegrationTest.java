package com.grocery.pricing;

import com.grocery.common.id.Ids;
import com.grocery.pricing.event.CatalogEventsListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.UUID;

import static com.grocery.pricing.AbstractIntegrationTest.productUpserted;
import static com.grocery.pricing.AbstractIntegrationTest.vendor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A second application context whose Redis is unreachable (a closed port): quotes are answered from MySQL and
 * price changes still commit; no Redis error reaches the caller.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // ADR-0011: the in-memory Schema Registry; tests register the central schemas themselves
        "grocery.events.avro.schema-registry-url=mock://pricing-service",
        "grocery.events.avro.auto-register-schemas=true",
        // Phase 12: the coupon sweeper is run by hand in the tests
        "grocery.pricing.coupons.sweeper-enabled=false"
})
@AutoConfigureMockMvc
class RedisUnavailableIntegrationTest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.MYSQL::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.MYSQL::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", RedisUnavailableIntegrationTest::closedPort);
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
    CatalogEventsListener catalogEvents;

    @Test
    void quotesAndPriceChangesWorkWithoutRedis() throws Exception {
        UUID vendorId = UUID.randomUUID();
        UUID productId = Ids.newId();
        catalogEvents.on(productUpserted(Ids.newId().toString(), productId, vendorId, "Ghee", null, "PUBLISHED", 1));

        for (String sellingPrice : new String[]{"500.00", "520.00"}) {          // after-commit cache writes fail
            mvc.perform(put("/api/vendor/prices/{id}", productId).with(vendor(vendorId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mrp\":600.00,\"sellingPrice\":%s}".formatted(sellingPrice)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("APPLIED"));
        }

        for (int i = 0; i < 2; i++) {                                         // failing, then bypassed
            mvc.perform(post("/internal/prices/quote").with(AbstractIntegrationTest.as(UUID.randomUUID(), "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productIds\":[\"%s\",\"%s\"]}".formatted(productId, Ids.newId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].productId").value(productId.toString()))
                    .andExpect(jsonPath("$.items[0].sellingPrice").value(520.00))
                    .andExpect(jsonPath("$.items[0].priceVersion").value(2))
                    .andExpect(jsonPath("$.missing.length()").value(1));
        }
    }
}
