package com.grocery.restaurant;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventSchemas;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import com.grocery.common.id.Ids;
import com.grocery.restaurant.entity.RejectionReason;
import com.grocery.restaurant.event.RestaurantEvents;
import com.grocery.restaurant.event.RestaurantOrderEvents;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011: every event restaurant-service publishes is also written to the outbox as Avro ({@code payload_avro}, the
 * column Debezium publishes): Confluent wire format, written with the central schema under the subject
 * {@code <topic>-com.grocery.events.<domain>.<EventType>}, and decoded the way every consumer reads it (grocery-common's
 * {@link AvroEventDeserializer}) it is the JSON stored in {@code payload}.
 */
class AvroOutboxRoundTripIntegrationTest extends StoreOrderTestSupport {

    private static final String OWNER = "RESTAURANT_OWNER";
    private static final List<String> EVENT_TYPES = List.of(RestaurantEvents.REGISTERED, RestaurantEvents.SUBMITTED,
            RestaurantEvents.APPROVED, RestaurantEvents.REJECTED, RestaurantEvents.SUSPENDED,
            RestaurantEvents.REINSTATED, RestaurantEvents.LISTING_UPDATED,
            // Phase 8: the store order events (aggregate RestaurantOrder)
            RestaurantOrderEvents.RECEIVED, RestaurantOrderEvents.ACCEPTED, RestaurantOrderEvents.REJECTED,
            RestaurantOrderEvents.PREPARING, RestaurantOrderEvents.READY_FOR_PICKUP);
    // line2 left out (null); two days of opening hours, one closing after midnight
    private static final String COMPLETE_PROFILE = """
            {"name":"Avro Kitchen","description":"Schemas and biryani","cuisines":["NORTH_INDIAN","BIRYANI"],
             "fssaiLicense":"12345678901234","contactEmail":"ops@avrokitchen.in","contactPhone":"+919876543210",
             "address":{"line1":"5 Church Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                        "country":"IN","latitude":12.9752,"longitude":77.6050},
             "openingHours":[{"dayOfWeek":"SUNDAY","opensAt":"18:00","closesAt":"02:00"},
                             {"dayOfWeek":"MONDAY","opensAt":"09:00","closesAt":"23:00"}]}""";

    @Autowired
    GroceryAvroProperties avroProperties;

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final AvroEventSchemas schemas = new AvroEventSchemas();
    private final UUID admin = UUID.randomUUID();

    private record OutboxRow(String aggregateId, String topic, String eventType, int eventVersion, String payload,
                             byte[] payloadAvro) {
    }

    private ResultActions send(UUID owner, String body, String path, Object... vars) throws Exception {
        return mvc.perform(post(path, vars).with(as(owner, OWNER)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String idOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void everyRestaurantEventIsAvroThatDecodesToTheJsonPayload() throws Exception {
        // Draft with a menu: categories with and without description, an empty category, items with a 1-decimal
        // price, an image and a sold-out one
        UUID owner = UUID.randomUUID();
        String restaurantId = json.readTree(mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
        String mains = idOf(send(owner, "{\"name\":\"Mains\",\"description\":\"Chef's picks\",\"sortOrder\":1}",
                "/api/restaurant/menu/categories").andExpect(status().isCreated()));
        String drinks = idOf(send(owner, "{\"name\":\"Drinks\",\"sortOrder\":0}", "/api/restaurant/menu/categories")
                .andExpect(status().isCreated()));
        send(owner, "{\"name\":\"Coming soon\",\"sortOrder\":5}", "/api/restaurant/menu/categories")
                .andExpect(status().isCreated());
        String biryani = idOf(send(owner, """
                        {"categoryId":"%s","name":"Chicken Biryani","description":"House special","price":249,
                         "foodType":"NON_VEG","imageUrl":"https://img.example.com/biryani.jpg","sortOrder":0}"""
                        .formatted(mains), "/api/restaurant/menu/items").andExpect(status().isCreated()));
        String eggCurry = idOf(send(owner, """
                        {"categoryId":"%s","name":"Egg Curry","price":199.5,"foodType":"EGG","sortOrder":1}"""
                        .formatted(mains), "/api/restaurant/menu/items").andExpect(status().isCreated()));
        send(owner, """
                {"categoryId":"%s","name":"Masala Chai","price":30.00,"foodType":"VEG"}""".formatted(drinks),
                "/api/restaurant/menu/items").andExpect(status().isCreated());
        mvc.perform(patch("/api/restaurant/menu/items/{id}/availability", eggCurry).with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":false}"))
                .andExpect(status().isOk());

        // The whole lifecycle; approval, suspension, reinstatement and menu changes publish the listing
        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/restaurants/{id}/reject", restaurantId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"FSSAI licence expired\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/restaurants/{id}/approve", restaurantId).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(put("/api/restaurant/menu/items/{id}", biryani).with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"categoryId":"%s","name":"Chicken Biryani","price":259.10,"foodType":"NON_VEG"}"""
                                .formatted(mains)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/restaurant/accepting-orders").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptingOrders\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/restaurants/{id}/suspend", restaurantId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Hygiene complaint\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(post("/api/admin/restaurants/{id}/reinstate", restaurantId).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // Republish: listings rebuilt from the database rows
        mvc.perform(post("/api/admin/restaurants/listings/republish").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isOk());
        // A draft from the registration event without a name: name is null in both formats
        UUID nameless = UUID.randomUUID();
        onboarding.onRestaurantOwnerRegistered(Ids.newId().toString(), nameless, null, "nameless@example.com", null);

        // Phase 8 store orders of the approved restaurant: received → accepted (35 min) → preparing → ready;
        // received → rejected with a note; received → auto-rejected (no note, no actor); fractional rupees
        Store store = new Store(UUID.fromString(restaurantId), owner);
        UUID served = confirm(store, List.of(line(Ids.newId(), "Chicken Biryani", 2, "259.10"),
                line(Ids.newId(), "Masala Chai", 1, "29.99")));
        storeOrders.accept(ownerUser(store), served, 35);
        storeOrders.preparing(ownerUser(store), served);
        storeOrders.ready(ownerUser(store), served);
        UUID declined = confirm(store);
        storeOrders.reject(ownerUser(store), declined, RejectionReason.OTHER, "Tandoor is down");
        UUID unanswered = confirm(store);
        pollerAhead(Duration.ofMinutes(6)).rejectExpired();
        assertThat(storeEventTypes(served)).containsExactly(RestaurantOrderEvents.RECEIVED,
                RestaurantOrderEvents.ACCEPTED, RestaurantOrderEvents.PREPARING, RestaurantOrderEvents.READY_FOR_PICKUP);
        assertThat(storeEventTypes(declined)).containsExactly(RestaurantOrderEvents.RECEIVED,
                RestaurantOrderEvents.REJECTED);
        assertThat(storeEventTypes(unanswered)).containsExactly(RestaurantOrderEvents.RECEIVED,
                RestaurantOrderEvents.REJECTED);
        assertThat(storeEvent(unanswered, RestaurantOrderEvents.REJECTED).get("actorUserId").isNull()).isTrue();

        List<OutboxRow> rows = JdbcClient.create(dataSource).sql("""
                        SELECT aggregate_id, topic, event_type, event_version, payload, payload_avro
                        FROM outbox_event ORDER BY occurred_at, id""")
                .query((rs, n) -> new OutboxRow(rs.getString("aggregate_id"), rs.getString("topic"),
                        rs.getString("event_type"), rs.getInt("event_version"), rs.getString("payload"),
                        rs.getBytes("payload_avro")))
                .list();
        List<OutboxRow> ours = rows.stream().filter(row -> row.aggregateId().equals(restaurantId)).toList();
        assertThat(ours).extracting(OutboxRow::eventType).containsSubsequence(RestaurantEvents.REGISTERED,
                RestaurantEvents.SUBMITTED, RestaurantEvents.REJECTED, RestaurantEvents.SUBMITTED,
                RestaurantEvents.APPROVED, RestaurantEvents.LISTING_UPDATED, RestaurantEvents.SUSPENDED,
                RestaurantEvents.LISTING_UPDATED, RestaurantEvents.REINSTATED, RestaurantEvents.LISTING_UPDATED);
        JsonNode firstListing = converter.readExact(ours.stream()
                .filter(row -> row.eventType().equals(RestaurantEvents.LISTING_UPDATED)).findFirst().orElseThrow()
                .payload());
        assertThat(firstListing.get("menu").size()).isEqualTo(3);
        assertThat(firstListing.get("address").get("line2").isNull()).isTrue();
        List<String> namelessPayloads = rows.stream()
                .filter(row -> !row.aggregateId().equals(restaurantId) && row.payload().contains(nameless.toString()))
                .map(OutboxRow::payload).toList();
        assertThat(namelessPayloads).hasSize(1);
        assertThat(converter.readExact(namelessPayloads.getFirst()).get("name").isNull()).isTrue();
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
        assertThat(registry.getAllSubjects()).containsAll(EVENT_TYPES.stream()
                .map(type -> "restaurant.events-com.grocery.events.restaurant." + type).toList());
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
