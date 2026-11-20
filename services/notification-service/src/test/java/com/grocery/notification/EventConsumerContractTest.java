package com.grocery.notification;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.events.contract.ConsumerContract;
import com.grocery.notification.event.DispatchListener;
import com.grocery.notification.event.LocationListener;
import com.grocery.notification.service.DispatchService;
import com.grocery.notification.service.LocationService;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.stream.Stream;

/**
 * Event consumer contract (docs/api/phase-13-observability-and-testing.md §4): notification-service's two consumers
 * accept a valid sample of every event type on their topics, generated from grocery-event-schemas in the Confluent wire
 * format and read through the service's own Avro deserializer, with all optional fields set and with all of them null,
 * delivered twice:
 * <ul>
 *   <li>the dispatch consumer (ten topics): the event handlers of package {@code dispatch} decide (their
 *       {@code supports}); a fresh event updates projections and notifies, a payment event of an unknown order is
 *       parked, the redelivery is a duplicate, unsupported types are ignored without touching the database;</li>
 *   <li>the location consumer (delivery.location): best effort, never throws by design.</li>
 * </ul>
 */
class EventConsumerContractTest extends AbstractIntegrationTest {

    static final ConsumerContract CONTRACT = ConsumerContract.builder()
            .listener(DispatchListener.class, DispatchService.class)
            .dispatchPackages("com.grocery.notification.dispatch")
            .listener(LocationListener.class, LocationService.class)
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
