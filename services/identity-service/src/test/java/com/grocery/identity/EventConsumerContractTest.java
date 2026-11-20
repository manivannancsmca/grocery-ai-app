package com.grocery.identity;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.events.contract.ConsumerContract;
import com.grocery.identity.event.BusinessEventsListener;
import com.grocery.identity.event.CustomerEventsListener;
import com.grocery.identity.service.BusinessMembershipService;
import com.grocery.identity.service.CustomerProfileSyncService;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.stream.Stream;

/**
 * Event consumer contract (docs/api/phase-13-observability-and-testing.md §4): identity-service's business-events
 * listener (vendor.events + restaurant.events → store membership claims) accepts a valid sample of every event type on
 * both topics, generated from grocery-event-schemas in the Confluent wire format and read through the service's own
 * Avro deserializer, with all optional fields set and with all of them null, delivered twice. The dispatch is
 * {@code "Vendor"/"Restaurant"} prefix + lifecycle suffix in {@link BusinessMembershipService}; store-order, listing and
 * purchasing events are ignored by design. The customer-events listener (customer.events → the account's name and phone)
 * gets CustomerProfileUpdated of a customer it does not know, which it skips.
 */
class EventConsumerContractTest extends AbstractIntegrationTest {

    static final ConsumerContract CONTRACT = ConsumerContract.builder()
            .listener(BusinessEventsListener.class, BusinessMembershipService.class)
            .listener(CustomerEventsListener.class, CustomerProfileSyncService.class)
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
