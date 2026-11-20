package com.grocery.catalog;

import com.grocery.catalog.event.VendorEventsListener;
import com.grocery.common.id.Ids;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** vendor.events → vendor_ref projection → product re-snapshots (the listener is called directly). */
class VendorEventsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    VendorEventsListener listener;

    private final UUID vendorId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private String categoryId;

    @BeforeEach
    void category() throws Exception {
        categoryId = createCategory(unique("Fruits"), null);
    }

    private String product(String name) throws Exception {
        return body(mvc.perform(post("/api/vendor/products").with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"categoryId\":\"%s\",\"unitValue\":6,\"unitOfMeasure\":\"PCS\"}"
                                .formatted(name, categoryId)))
                .andExpect(status().isCreated())).get("id").asString();
    }

    private ConsumerRecord<String, String> event(String eventId, String eventType, String businessName) {
        String payload = """
                {"vendorId":"%s","ownerUserId":"%s","businessName":%s,"reason":null,"actorUserId":null}"""
                .formatted(vendorId, ownerId, businessName == null ? "null" : "\"" + businessName + "\"");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("vendor.events", 0, 0L, vendorId.toString(), payload);
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", "Vendor".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private String vendorStatus() {
        return JdbcClient.create(dataSource)
                .sql("SELECT status FROM vendor_ref WHERE vendor_id = UUID_TO_BIN(?)").param(vendorId.toString())
                .query(String.class).optional().orElse(null);
    }

    @Test
    void suspensionAndReinstatementReSnapshotTheVendorsProductsExactlyOncePerEvent() throws Exception {
        String apples = product("Apples");
        String bananas = product("Bananas");
        String archived = product("Old Plums");
        mvc.perform(delete("/api/vendor/products/{id}", archived).with(vendor(vendorId))).andExpect(status().isNoContent());
        // Created before VendorApproved was consumed: active, no name yet
        assertThat(lastProductEvent(apples).get("vendorActive").asBoolean()).isTrue();
        assertThat(lastProductEvent(apples).get("vendorName").isNull()).isTrue();

        listener.on(event(Ids.newId().toString(), "VendorApproved", "Green Basket"));
        assertThat(vendorStatus()).isEqualTo("APPROVED");
        JsonNode approved = lastProductEvent(apples);
        assertThat(approved.get("vendorName").asString()).isEqualTo("Green Basket");
        assertThat(approved.get("vendorActive").asBoolean()).isTrue();
        assertThat(approved.get("productVersion").asLong()).isEqualTo(2);
        int archivedEvents = productEvents(archived).size();

        String suspension = Ids.newId().toString();
        listener.on(event(suspension, "VendorSuspended", "Green Basket"));
        listener.on(event(suspension, "VendorSuspended", "Green Basket"));     // redelivery
        assertThat(vendorStatus()).isEqualTo("SUSPENDED");
        for (String product : new String[]{apples, bananas}) {
            JsonNode snapshot = lastProductEvent(product);
            assertThat(snapshot.get("vendorActive").asBoolean()).isFalse();
            assertThat(snapshot.get("vendorName").asString()).isEqualTo("Green Basket");
            assertThat(snapshot.get("productVersion").asLong()).isEqualTo(3);
        }
        assertThat(productEvents(apples)).hasSize(3);                         // processed once
        assertThat(productEvents(archived)).hasSize(archivedEvents);           // archived products are left alone

        mvc.perform(get("/api/admin/products/{id}", apples).with(as(admin, "SUPPORT")))
                .andExpect(jsonPath("$.vendorName").value("Green Basket"));

        listener.on(event(Ids.newId().toString(), "VendorReinstated", "Green Basket"));
        assertThat(lastProductEvent(bananas).get("vendorActive").asBoolean()).isTrue();
        assertThat(lastProductEvent(bananas).get("productVersion").asLong()).isEqualTo(4);

        // Same status and name again: projection updated, no new snapshots
        listener.on(event(Ids.newId().toString(), "VendorApproved", null));
        assertThat(productEvents(bananas)).hasSize(4);
        // Unrelated event types are ignored
        listener.on(event(Ids.newId().toString(), "VendorOrderAccepted", "Renamed"));
        assertThat(productEvents(bananas)).hasSize(4);
        // A business-name change re-snapshots too
        listener.on(event(Ids.newId().toString(), "VendorApproved", "Green Basket Fresh"));
        assertThat(lastProductEvent(bananas).get("vendorName").asString()).isEqualTo("Green Basket Fresh");
        assertThat(lastProductEvent(bananas).get("productVersion").asLong()).isEqualTo(5);
    }

    @Test
    void eventsForVendorsWithoutProductsOnlyUpdateTheProjection() {
        listener.on(event(Ids.newId().toString(), "VendorRegistered", "New Shop"));
        assertThat(vendorStatus()).isEqualTo("DRAFT");
        listener.on(event(Ids.newId().toString(), "VendorSubmitted", "New Shop"));
        assertThat(vendorStatus()).isEqualTo("PENDING_APPROVAL");
        listener.on(event(Ids.newId().toString(), "VendorRejected", "New Shop"));
        assertThat(vendorStatus()).isEqualTo("REJECTED");
    }
}
