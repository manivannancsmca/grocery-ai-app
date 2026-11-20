package com.grocery.customer;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.customer.event.IdentityEventsListener;
import com.grocery.events.contract.ConsumerContract;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.stream.Stream;

/**
 * Event consumer contract (docs/api/phase-13-observability-and-testing.md §4): customer-service's identity.events
 * listener accepts a valid sample of every identity event type, generated from grocery-event-schemas in the Confluent
 * wire format and read through the service's own Avro deserializer, with all optional fields set and with all of them
 * null, delivered twice. UserRegistered (role CUSTOMER) creates the profile, the redelivery is a duplicate; the other
 * types are ignored by design.
 */
class EventConsumerContractTest extends AbstractIntegrationTest {

    static final ConsumerContract CONTRACT = ConsumerContract.builder()
            .listener(IdentityEventsListener.class)
            .value("role", "CUSTOMER")
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
