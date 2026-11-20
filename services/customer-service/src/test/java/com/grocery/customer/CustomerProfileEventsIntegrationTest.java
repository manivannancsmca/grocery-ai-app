package com.grocery.customer;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import com.grocery.common.id.Ids;
import com.grocery.customer.service.CustomerProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * customer.events CustomerProfileUpdated: written to the outbox in the profile's transaction, only when the name or the
 * phone changed, as Avro that decodes to the JSON payload (what identity and notification consume).
 */
class CustomerProfileEventsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CustomerProfileService profiles;
    @Autowired
    DataSource dataSource;
    @Autowired
    GroceryAvroProperties avroProperties;

    private record Row(String topic, String aggregateType, String payload, byte[] payloadAvro) {
    }

    private List<Row> events(UUID customerId) {
        return JdbcClient.create(dataSource).sql("""
                        SELECT topic, aggregate_type, payload, payload_avro FROM outbox_event
                        WHERE aggregate_id = ? AND event_type = 'CustomerProfileUpdated' ORDER BY occurred_at, id""")
                .param(customerId.toString())
                .query((rs, n) -> new Row(rs.getString("topic"), rs.getString("aggregate_type"),
                        rs.getString("payload"), rs.getBytes("payload_avro")))
                .list();
    }

    private void save(UUID customer, String body) throws Exception {
        mvc.perform(put("/api/customers/me").with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void aChangedNameOrPhoneIsPublishedOnceAsAvro() throws Exception {
        UUID customer = UUID.randomUUID();
        profiles.onCustomerUserRegistered(Ids.newId().toString(), customer, "Kavya", "kavya@example.com",
                "+919840066001");

        save(customer, "{\"fullName\":\" Kavya Sriram \",\"phone\":\"+919840066001\"}");
        save(customer, "{\"fullName\":\"Kavya Sriram\",\"phone\":\"+919840066001\"}");   // nothing changed

        List<Row> rows = events(customer);
        assertThat(rows).hasSize(1);
        Row row = rows.getFirst();
        assertThat(row.topic()).isEqualTo("customer.events");
        assertThat(row.aggregateType()).isEqualTo("Customer");
        JsonNode payload = json.readTree(row.payload());
        assertThat(payload.get("customerId").asString()).isEqualTo(customer.toString());
        assertThat(payload.get("fullName").asString()).isEqualTo("Kavya Sriram");
        assertThat(payload.get("phone").asString()).isEqualTo("+919840066001");
        assertThat(Instant.parse(payload.get("updatedAt").asString())).isBefore(Instant.now().plusSeconds(1));

        // What Debezium publishes: Confluent wire format, decoded like every consumer does
        JsonAvroConverter converter = new JsonAvroConverter();
        AvroEventDeserializer deserializer = new AvroEventDeserializer(
                SchemaRegistrySupport.newClient(avroProperties), avroProperties, converter);
        assertThat(AvroEventDeserializer.isAvro(row.payloadAvro())).isTrue();
        JsonNode decoded = converter.readExact(deserializer.deserialize(row.topic(), row.payloadAvro()));
        assertThat(decoded.get("customerId").asString()).isEqualTo(customer.toString());
        assertThat(decoded.get("fullName").asString()).isEqualTo("Kavya Sriram");
        assertThat(decoded.get("phone").asString()).isEqualTo("+919840066001");
        assertThat(Instant.parse(decoded.get("updatedAt").asString()))   // timestamp-micros
                .isEqualTo(Instant.parse(payload.get("updatedAt").asString()).truncatedTo(ChronoUnit.MICROS));

        // Clearing the phone is a change too (null in the event)
        save(customer, "{\"fullName\":\"Kavya Sriram\"}");
        List<Row> after = events(customer);
        assertThat(after).hasSize(2);
        assertThat(json.readTree(after.getLast().payload()).get("phone").isNull()).isTrue();
    }

    @Test
    void aProfileCreatedOnDemandPublishesOnlyWhenItDiffersFromTheToken() throws Exception {
        UUID unchanged = UUID.randomUUID();
        save(unchanged, "{\"fullName\":\"%s\"}".formatted(nameOf(unchanged)));
        assertThat(events(unchanged)).as("same as the account's name: nothing to tell").isEmpty();

        UUID renamed = UUID.randomUUID();
        save(renamed, "{\"fullName\":\"Early Bird\"}");
        assertThat(events(renamed)).hasSize(1);
    }
}
