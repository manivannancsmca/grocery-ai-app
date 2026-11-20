package com.grocery.catalogquery;

import co.elastic.clients.json.JsonData;
import com.grocery.catalogquery.TestEvents.Cat;
import com.grocery.catalogquery.event.CatalogEventsListener;
import com.grocery.catalogquery.event.InventoryEventsListener;
import com.grocery.catalogquery.event.PricingEventsListener;
import com.grocery.catalogquery.event.RestaurantEventsListener;
import com.grocery.catalogquery.event.ReviewEventsListener;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.JsonAvroConverter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.grocery.catalogquery.TestEvents.availability;
import static com.grocery.catalogquery.TestEvents.item;
import static com.grocery.catalogquery.TestEvents.price;
import static com.grocery.catalogquery.TestEvents.product;
import static com.grocery.catalogquery.TestEvents.ratingSummary;
import static com.grocery.catalogquery.TestEvents.restaurant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011 on the consumer side: an event that went through the real wire path (encoded as the producer's outbox
 * does, with grocery-common's encoder against the in-memory registry, then decoded by the value deserializer this
 * service's consumer factory installs) indexes exactly the same document, and serves exactly the same API response,
 * as the same event as plain JSON. Avro renders decimals with their schema scale ({@code unitValue} 500 becomes
 * {@code 500.000}) and timestamps with microsecond precision; neither may change what is indexed.
 */
class AvroEventPathIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AvroEventEncoder encoder;
    @Autowired
    JsonAvroConverter converter;
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    private final List<Cat> vegetables = List.of(Cat.of("Fruits & Vegetables"), Cat.of("Fresh Vegetables"));
    private final String vendor = id();

    @Test
    void theConsumersReadValuesWithTheAvroDeserializer() {
        assertThat(consumerFactory.getValueDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
    }

    @Test
    void productPriceAndStockEventsIndexTheSameDocumentAsPlainJson() throws Exception {
        // unit values as catalog-command writes them (trailing zeros stripped); Avro carries them at scale 3
        for (String unitValue : List.of("500", "1.5", "0.125")) {
            String plain = id();
            String viaAvro = id();
            Map<String, Object> snapshot = product(plain, vendor, "Basmati Rice", 3, vegetables);
            snapshot.put("unitValue", new BigDecimal(unitValue));
            snapshot.put("unitOfMeasure", "KG");
            snapshot.put("unitLabel", unitValue + " kg");

            JsonNode decoded = both(plain, viaAvro, CatalogEventsListener.TOPIC, "ProductUpserted", snapshot,
                    catalogEvents::on);
            assertThat(decoded.get("unitValue").decimalValue().scale()).as("Avro renders decimal(10,3)").isEqualTo(3);
            both(plain, viaAvro, PricingEventsListener.TOPIC, "PriceChanged",
                    price(plain, vendor, "120.00", "99.50", 2), pricingEvents::on);
            both(plain, viaAvro, InventoryEventsListener.TOPIC, "ProductAvailabilityChanged",
                    availability(plain, vendor, true, 4), inventoryEvents::on);
            refresh();

            assertThat(stored("products", viaAvro, plain)).as("indexed document, unitValue " + unitValue)
                    .isEqualTo(stored("products", plain, plain));
            assertThat(response("/api/catalog/products/{id}", viaAvro, plain)).as("product detail, unitValue " + unitValue)
                    .isEqualTo(response("/api/catalog/products/{id}", plain, plain));
        }
    }

    @Test
    void aRestaurantListingIndexesTheSameDocumentAsPlainJson() throws Exception {
        String plain = id();
        String viaAvro = id();
        Map<String, Object> listing = restaurant(plain, "Spice Route", 2, 12.9756, 77.6066,
                List.of(item("Chicken Biryani", "249.00", "NON_VEG"), item("Masala Dosa", "120.50", "VEG")));
        listing.put("updatedAt", "2026-11-11T10:15:30.123456Z");

        both(plain, viaAvro, RestaurantEventsListener.TOPIC, "RestaurantListingUpdated", listing, restaurantEvents::on);
        refresh();

        assertThat(stored("restaurants", viaAvro, plain)).isEqualTo(stored("restaurants", plain, plain));
        assertThat(response("/api/catalog/restaurants/{id}", viaAvro, plain))
                .isEqualTo(response("/api/catalog/restaurants/{id}", plain, plain));
    }

    @Test
    void ratingSummariesIndexTheSameAsPlainJson() throws Exception {
        // products: the 1.5.0 RatingSummaryUpdated (averageRating decimal(3,2), timestamp-micros) on a product …
        String plain = id();
        String viaAvro = id();
        publishCatalogEvent("ProductUpserted", plain, product(plain, vendor, "Tomato", 1, vegetables));
        publishCatalogEvent("ProductUpserted", viaAvro, product(viaAvro, vendor, "Tomato", 1, vegetables));
        JsonNode decoded = both(plain, viaAvro, ReviewEventsListener.TOPIC, "RatingSummaryUpdated",
                ratingSummary("PRODUCT", plain, 3, 0, 1, 0, 0, 2), reviewEvents::on);
        assertThat(decoded.get("averageRating").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(decoded.get("ratingDistribution")).hasSize(5);
        // … and on a restaurant
        String plainRestaurant = id();
        String avroRestaurant = id();
        Map<String, Object> listing = restaurant(plainRestaurant, "Spice Route", 1, 12.9756, 77.6066,
                List.of(item("Masala Dosa", "120.50", "VEG")));
        publishRestaurantEvent("RestaurantListingUpdated", plainRestaurant, listing);
        Map<String, Object> sameListing = new java.util.LinkedHashMap<>(listing);      // same menu ids
        sameListing.put("restaurantId", avroRestaurant);
        publishRestaurantEvent("RestaurantListingUpdated", avroRestaurant, sameListing);
        both(plainRestaurant, avroRestaurant, ReviewEventsListener.TOPIC, "RatingSummaryUpdated",
                ratingSummary("RESTAURANT", plainRestaurant, 7, 1, 0, 0, 1, 1), reviewEvents::on);
        refresh();

        assertThat(stored("products", viaAvro, plain)).isEqualTo(stored("products", plain, plain));
        assertThat(response("/api/catalog/products/{id}", viaAvro, plain))
                .isEqualTo(response("/api/catalog/products/{id}", plain, plain));
        assertThat(stored("restaurants", avroRestaurant, plainRestaurant))
                .isEqualTo(stored("restaurants", plainRestaurant, plainRestaurant));
        assertThat(response("/api/catalog/restaurants/{id}", avroRestaurant, plainRestaurant).get("rating")
                .decimalValue()).isEqualByComparingTo("3.33");
    }

    /**
     * Hands {@code payload} (about {@code plainId}) to the listener as plain JSON, and the same payload about
     * {@code avroId} through the Avro wire path. Returns the JSON the Avro path produced.
     */
    private JsonNode both(String plainId, String avroId, String topic, String eventType, Map<String, Object> payload,
                          Consumer<ConsumerRecord<String, String>> listener) {
        ConsumerRecord<String, String> plain = record(topic, plainId, eventType, payload);
        listener.accept(plain);

        String json = plain.value().replace(plainId, avroId);
        byte[] wire = encoder.encode(topic, eventType, 1, converter.readExact(json));
        assertThat(wire[0]).as("Confluent magic byte").isZero();
        @SuppressWarnings("unchecked")
        Deserializer<String> values = (Deserializer<String>) consumerFactory.getValueDeserializer();
        String decoded = values.deserialize(topic, new RecordHeaders(), wire);
        assertThat(decoded).as(eventType + " decoded from Avro").isNotNull();

        ConsumerRecord<String, String> viaAvro = new ConsumerRecord<>(topic, 0, 0L, avroId, decoded);
        for (Header header : plain.headers()) {
            viaAvro.headers().add(header);
        }
        listener.accept(viaAvro);
        return converter.readExact(decoded);
    }

    /** The stored {@code _source} of {@code id}, with {@code id} replaced by {@code as}. */
    private JsonNode stored(String index, String id, String as) throws Exception {
        String source = elasticsearch.get(g -> g.index(index).id(id), JsonData.class)
                .source().toJson(elasticsearch._jsonpMapper()).toString();
        return json.readTree(source.replace(id, as));
    }

    /** The public API response for {@code id}, with {@code id} replaced by {@code as}. */
    private JsonNode response(String path, String id, String as) throws Exception {
        String body = mvc.perform(get(path, id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body.replace(id, as));
    }
}
