package com.grocery.restaurant;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.events.contract.ConsumerContract;
import com.grocery.restaurant.event.IdentityEventsListener;
import com.grocery.restaurant.event.OrderEventsListener;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.stream.Stream;

/**
 * Event consumer contract (docs/api/phase-13-observability-and-testing.md §4): restaurant-service's listeners (order
 * events → the restaurant's order board, identity events → restaurant owners) accept a valid sample of every event type
 * on their topics, generated from grocery-event-schemas in the Confluent wire format and read through the service's
 * own Avro deserializer, with all optional fields set and with all of them null, delivered twice. Samples are for this
 * store type (orderType RESTAURANT, role RESTAURANT_OWNER), so the board and onboarding paths run, not the
 * other-store-type guard.
 */
class EventConsumerContractTest extends AbstractIntegrationTest {

    static final ConsumerContract CONTRACT = ConsumerContract.builder()
            .listener(OrderEventsListener.class)
            .listener(IdentityEventsListener.class)
            .value("orderType", "RESTAURANT")
            .value("role", "RESTAURANT_OWNER")
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
