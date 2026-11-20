package com.grocery.identity;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventSchemas;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011: every event identity-service publishes is also written to the outbox as Avro ({@code payload_avro}, the
 * column Debezium publishes): Confluent wire format, written with the central schema under the subject
 * {@code <topic>-com.grocery.events.<domain>.<EventType>}, and decoded the way every consumer reads it (grocery-common's
 * {@link AvroEventDeserializer}) it is the JSON stored in {@code payload}.
 */
class AvroOutboxRoundTripIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> EVENT_TYPES = List.of("UserRegistered", "UserSuspended", "UserReactivated",
            "PasswordResetRequested", "PasswordChanged", "SecurityEventRecorded");

    @Autowired
    DataSource dataSource;
    @Autowired
    GroceryAvroProperties avroProperties;

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final AvroEventSchemas schemas = new AvroEventSchemas();

    private record OutboxRow(String aggregateId, String topic, String eventType, int eventVersion, String payload,
                             byte[] payloadAvro) {
    }

    @Test
    void everyIdentityEventIsAvroThatDecodesToTheJsonPayload() throws Exception {
        String admin = accessToken(login(ADMIN_EMAIL, ADMIN_PASSWORD));
        // UserRegistered with every field set, then suspended and reactivated by the admin
        String email = uniqueEmail("avro");
        MockHttpServletResponse registered = register(email, "VENDOR");
        String userId = body(registered).at("/user/id").asString();
        mvc.perform(post("/api/admin/users/{id}/suspend", userId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Avro round trip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(post("/api/admin/users/{id}/reactivate", userId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // PasswordResetRequested (forgot), then PasswordChanged (change, signed in)
        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/auth/password/change").header("Authorization", "Bearer " + accessToken(registered))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"Changed123\"}".formatted(PASSWORD)))
                .andExpect(status().isNoContent());
        // UserRegistered without the optional phone (null in both formats)
        String withoutPhone = body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"No Phone","role":"CUSTOMER"}
                                """.formatted(uniqueEmail("avro-nophone"), PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse()).at("/user/id").asString();

        List<OutboxRow> rows = JdbcClient.create(dataSource).sql("""
                        SELECT aggregate_id, topic, event_type, event_version, payload, payload_avro
                        FROM outbox_event ORDER BY occurred_at, id""")
                .query((rs, n) -> new OutboxRow(rs.getString("aggregate_id"), rs.getString("topic"),
                        rs.getString("event_type"), rs.getInt("event_version"), rs.getString("payload"),
                        rs.getBytes("payload_avro")))
                .list();
        assertThat(rows).filteredOn(row -> row.aggregateId().equals(userId)).extracting(OutboxRow::eventType)
                .containsExactly("UserRegistered", "UserSuspended", "UserReactivated", "PasswordResetRequested",
                        "PasswordChanged");
        List<OutboxRow> noPhone = rows.stream().filter(row -> row.aggregateId().equals(withoutPhone)).toList();
        assertThat(noPhone).extracting(OutboxRow::eventType).containsExactly("UserRegistered");
        assertThat(converter.readExact(noPhone.getFirst().payload()).get("phone").isNull()).isTrue();
        assertThat(rows).extracting(OutboxRow::eventType).containsAll(EVENT_TYPES);

        // Every row in the table (also those written by the other test classes), decoded like a consumer does
        SchemaRegistryClient registry = SchemaRegistrySupport.newClient(avroProperties);
        AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avroProperties, converter);
        for (OutboxRow row : rows) {
            String what = row.topic() + " " + row.eventType() + " of " + row.aggregateId();
            byte[] avro = row.payloadAvro();
            assertThat(avro).as("payload_avro of %s", what).isNotNull();
            assertThat(avro[0]).as("magic byte of %s", what).isZero();
            assertThat(AvroEventDeserializer.isAvro(avro)).as("wire format of %s", what).isTrue();

            String decoded = deserializer.deserialize(row.topic(), avro);
            assertSameJson(what, converter.readExact(decoded), converter.readExact(row.payload()));

            String domain = row.topic().substring(0, row.topic().indexOf('.'));
            String subject = row.topic() + "-com.grocery.events." + domain + "." + row.eventType();
            int schemaId = ByteBuffer.wrap(avro, 1, 4).getInt();
            assertThat(registry.getAllSubjects()).as("subjects").contains(subject);
            assertThat(registry.getId(subject, new AvroSchema(
                    schemas.require(row.topic(), row.eventType(), row.eventVersion()))))
                    .as("schema id of %s under subject %s", what, subject).isEqualTo(schemaId);
        }
        assertThat(registry.getAllSubjects()).contains(
                "identity.events-com.grocery.events.identity.UserRegistered",
                "identity.events-com.grocery.events.identity.UserSuspended",
                "identity.events-com.grocery.events.identity.UserReactivated",
                "identity.events-com.grocery.events.identity.PasswordResetRequested",
                "identity.events-com.grocery.events.identity.PasswordChanged",
                "identity.events-com.grocery.events.identity.SecurityEventRecorded");
    }

    /**
     * The decoded JSON equals the stored JSON: numbers compared by value (500 vs 500.000), timestamps as instants
     * truncated to microseconds (timestamp-micros), a missing property equal to an explicit null.
     */
    private static void assertSameJson(String path, JsonNode decoded, JsonNode stored) {
        if (isNull(decoded) || isNull(stored)) {
            assertThat(isNull(decoded)).as("%s: null in both", path).isEqualTo(isNull(stored));
        } else if (decoded.isNumber() && stored.isNumber()) {
            assertThat(decoded.decimalValue()).as(path).isEqualByComparingTo(stored.decimalValue());
        } else if (decoded.isString() && stored.isString()) {
            Instant decodedInstant = instant(decoded.asString());
            Instant storedInstant = instant(stored.asString());
            if (decodedInstant != null && storedInstant != null) {
                assertThat(decodedInstant.truncatedTo(ChronoUnit.MICROS)).as(path)
                        .isEqualTo(storedInstant.truncatedTo(ChronoUnit.MICROS));
            } else {
                assertThat(decoded.asString()).as(path).isEqualTo(stored.asString());
            }
        } else if (decoded.isObject() && stored.isObject()) {
            Set<String> names = new TreeSet<>();
            decoded.properties().stream().map(Map.Entry::getKey).forEach(names::add);
            stored.properties().stream().map(Map.Entry::getKey).forEach(names::add);
            for (String name : names) {
                assertSameJson(path + "." + name, decoded.path(name), stored.path(name));
            }
        } else if (decoded.isArray() && stored.isArray()) {
            assertThat(decoded.size()).as("%s: array size", path).isEqualTo(stored.size());
            for (int i = 0; i < decoded.size(); i++) {
                assertSameJson(path + "[" + i + "]", decoded.get(i), stored.get(i));
            }
        } else {
            assertThat(decoded).as(path).isEqualTo(stored);
        }
    }

    private static boolean isNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
