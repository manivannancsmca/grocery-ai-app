package com.grocery.order;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.events.contract.ConsumerContract;
import com.grocery.order.event.DeliveryEventsListener;
import com.grocery.order.event.InventoryEventsListener;
import com.grocery.order.event.PaymentEventsListener;
import com.grocery.order.event.RestaurantEventsListener;
import com.grocery.order.event.StoreOrderEvents;
import com.grocery.order.event.VendorEventsListener;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.stream.Stream;

/**
 * Event consumer contract (docs/api/phase-13-observability-and-testing.md §4): every listener of order-service accepts
 * a valid sample of every event type on its topics (inventory, payment, delivery, vendor and restaurant events),
 * generated from grocery-event-schemas in the Confluent wire format and read through the service's own Avro
 * deserializer, with all optional fields set and with all of them null, delivered twice. Handled types run through
 * the real handlers and state guards (unknown orders are marked and skipped); every other type is ignored by design.
 */
class EventConsumerContractTest extends AbstractIntegrationTest {

    static final ConsumerContract CONTRACT = ConsumerContract.builder()
            .listener(InventoryEventsListener.class)
            .listener(PaymentEventsListener.class)
            .listener(DeliveryEventsListener.class)
            .listener(VendorEventsListener.class, StoreOrderEvents.class)
            .listener(RestaurantEventsListener.class, StoreOrderEvents.class)
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
        CONTRACT.deliver(c, context.getBean(c.listenerClass()), registry,
                new AvroEventDeserializer(registry, avroProperties, converter));
    }
}
