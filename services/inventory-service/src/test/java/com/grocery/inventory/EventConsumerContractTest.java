package com.grocery.inventory;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.events.contract.ConsumerContract;
import com.grocery.events.contract.EventSample;
import com.grocery.inventory.event.CatalogEventsListener;
import com.grocery.inventory.event.OrderEventsListener;
import com.grocery.inventory.event.VendorEventsListener;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Event consumer contract (docs/api/phase-13-observability-and-testing.md §4): inventory-service's listeners (catalog
 * events → product copy, order events → reservations, vendor events → goods receipts) accept a valid sample of every
 * event type on their topics, generated from grocery-event-schemas in the Confluent wire format and read through the
 * service's own Avro deserializer, with all optional fields set and with all of them null, delivered twice. Samples of
 * unknown products and orders end in the documented outcomes (reservation failed, nothing to release, ...).
 */
class EventConsumerContractTest extends AbstractIntegrationTest {

    static final ConsumerContract CONTRACT = ConsumerContract.builder()
            .listener(CatalogEventsListener.class)
            .listener(OrderEventsListener.class)
            .listener(VendorEventsListener.class)
            .build();

    static Stream<ConsumerContract.Case> cases() {
        return CONTRACT.cases();
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private SchemaRegistryClient registry;
    @Autowired
    private GroceryAvroProperties avroProperties;
    @Autowired
    private JsonAvroConverter converter;

    @Test
    void everyEventTypeTheDispatchCodeNamesHasASchema() {
        System.out.println(CONTRACT.summary());
        CONTRACT.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void acceptsAValidSampleOfEverySubscribedEventType(ConsumerContract.Case c) {
        AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avroProperties, converter);
        CONTRACT.deliver(c, context.getBean(c.listenerClass()), registry, deserializer, sample -> {
            if (sample.schema().eventType().equals("GoodsReceived")) {
                // A receipt of a product inventory does not know yet is retried by design (RestockService: the product
                // may still be on its way from catalog.events), so the products arrive first, through this service's
                // own catalog listener, as schema-generated ProductUpserted samples of the receipt's vendor.
                for (String productId : sample.texts("productId")) {
                    EventSample product = EventSample.of("catalog.events", "ProductUpserted", Map.of(
                            "productId", productId, "vendorId", sample.text("vendorId"), "status", "PUBLISHED"));
                    context.getBean(CatalogEventsListener.class).on(product.consumerRecord(registry, deserializer));
                }
            }
        });
    }
}
