package com.grocery.events.contract;

import com.grocery.events.contract.ConsumerContract.Case;
import com.grocery.events.contract.ConsumerContract.ListenerReport;
import com.grocery.events.contract.EventSampleGenerator.Variant;
import com.grocery.events.contract.fixture.FixtureListeners.FailingListener;
import com.grocery.events.contract.fixture.FixtureListeners.LifecycleListener;
import com.grocery.events.contract.fixture.FixtureListeners.OrderListener;
import com.grocery.events.contract.fixture.FixtureListeners.UnknownTopicListener;
import com.grocery.events.contract.fixture.FixtureListeners.UnknownTypeListener;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericData;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The contract runner: listener discovery, dispatch-code scan, case generation, delivery and failure reporting. */
class ConsumerContractTest {

    private static final EventSchemaCatalog CATALOG = EventSchemaCatalog.fromClasspath();

    /** A stand-in for grocery-common's AvroEventDeserializer: wire format → Avro's JSON-like text. */
    static Deserializer<String> textDeserializer(SchemaRegistryClient registry) {
        KafkaAvroDeserializer avro = new KafkaAvroDeserializer(registry, Map.of("schema.registry.url", "mock://unused",
                "avro.use.logical.type.converters", false));
        return new Deserializer<>() {
            @Override
            public String deserialize(String topic, byte[] data) {
                return deserialize(topic, null, data);
            }

            @Override
            public String deserialize(String topic, Headers headers, byte[] data) {
                return GenericData.get().toString(avro.deserialize(topic, headers, data));
            }
        };
    }

    @Test
    void casesCoverEveryEventTypeOfTheSubscribedTopicsInBothVariants() {
        ConsumerContract contract = ConsumerContract.builder().listener(OrderListener.class).build();

        List<Case> cases = contract.cases().toList();

        assertThat(contract.problems()).isEmpty();
        assertThat(cases).hasSize(CATALOG.ofTopic("order.events").size() * 2);
        assertThat(cases).filteredOn(Case::handled).extracting(c -> c.schema().eventType())
                .containsOnly("OrderCreated", "OrderCancelled").hasSize(4);
        assertThat(cases).extracting(Case::variant).contains(Variant.FULL, Variant.MINIMAL);
        assertThat(cases.getFirst().toString()).matches("OrderListener\\.on <- order\\.events/\\w+ v1 \\[(handled|ignored), full]");
        ListenerReport report = contract.reports().getFirst();
        assertThat(report.handled()).isEqualTo(Map.of("order.events", Set.of("OrderCancelled", "OrderCreated")));
        assertThat(report.ignored().get("order.events")).contains("OrderConfirmed", "ReturnApproved");
        assertThat(contract.summary()).contains("OrderListener", "order.events: handled 2 [OrderCancelled, OrderCreated]");
    }

    @Test
    void aPrefixPlusSuffixDispatchCountsAsHandled() {
        ConsumerContract contract = ConsumerContract.builder().listener(LifecycleListener.class).build();

        assertThat(contract.problems()).isEmpty();
        assertThat(contract.cases().filter(Case::handled).map(c -> c.schema().eventType()).distinct())
                .containsExactlyInAnyOrder("VendorApproved", "VendorSuspended", "RestaurantApproved",
                        "RestaurantSuspended");
        assertThat(contract.cases().map(Case::topic).distinct()).containsExactly("vendor.events", "restaurant.events");
    }

    @Test
    void aDispatchedTypeWithoutSchemaOrATopicWithoutSchemasIsAProblem() {
        ConsumerContract unknownType = ConsumerContract.builder().listener(UnknownTypeListener.class).build();
        assertThat(unknownType.problems()).singleElement().asString().contains("'PaymentRefundedTwice'");
        assertThatThrownBy(unknownType::verify).isInstanceOf(AssertionError.class)
                .hasMessageContaining("PaymentRefundedTwice");

        ConsumerContract declared = ConsumerContract.builder().listener(UnknownTypeListener.class)
                .notEventType("PaymentRefundedTwice", "a test fixture").build();
        assertThat(declared.problems()).isEmpty();

        ConsumerContract unknownTopic = ConsumerContract.builder().listener(UnknownTopicListener.class).build();
        assertThat(unknownTopic.problems()).singleElement().asString().contains("'loyalty.events'");
        assertThat(unknownTopic.cases()).isEmpty();
    }

    @Test
    void aDispatchPackageIsScannedInTheListenersOwnClasspathEntry() {
        ConsumerContract contract = ConsumerContract.builder().listener(OrderListener.class)
                .dispatchPackages("com.grocery.events.contract.fixture").build();

        assertThat(contract.problems()).singleElement().asString().contains("'PaymentRefundedTwice'");
        assertThatThrownBy(() -> ConsumerContract.builder().listener(OrderListener.class)
                .dispatchPackages("com.grocery.events.nowhere").build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("com.grocery.events.nowhere");
    }

    @Test
    void deliveryRunsTheWireFormatThroughTheDeserializerAndTheListenerTwice() {
        SchemaRegistryClient registry = new MockSchemaRegistryClient();
        UUID orderId = UUID.randomUUID();
        ConsumerContract contract = ConsumerContract.builder().listener(OrderListener.class)
                .value("orderId", orderId).build();
        OrderListener listener = new OrderListener();
        Case created = contract.cases()
                .filter(c -> c.schema().eventType().equals("OrderCreated") && c.variant() == Variant.MINIMAL)
                .findFirst().orElseThrow();
        Case confirmed = contract.cases()
                .filter(c -> c.schema().eventType().equals("OrderConfirmed")).findFirst().orElseThrow();

        EventSample sample = contract.deliver(created, listener, registry, textDeserializer(registry),
                prepared -> assertThat(prepared.uuid("orderId")).contains(orderId));
        contract.deliver(confirmed, listener, registry, textDeserializer(registry));

        assertThat(listener.received).containsExactly("OrderCreated", "OrderCreated");     // delivery + redelivery
        assertThat(sample.key()).isEqualTo(orderId.toString());
    }

    @Test
    void aListenerFailureIsReportedWithWhatTheErrorHandlerWouldDo() {
        SchemaRegistryClient registry = new MockSchemaRegistryClient();
        ConsumerContract contract = ConsumerContract.builder().listener(FailingListener.class).build();
        Case reserved = contract.cases().filter(c -> c.schema().eventType().equals("StockReserved")).findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> contract.deliver(reserved, new FailingListener(), registry,
                textDeserializer(registry)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("inventory.events/StockReserved")
                .hasMessageContaining("retried 4 times, then dead-lettered")
                .hasMessageContaining("cannot map items")
                .hasMessageContaining("Payload: {\"orderId\"");
        assertThat(ConsumerContract.classify(new RuntimeException(new InvalidEventException())))
                .contains("dead-lettered at once");
    }

    @Test
    void handledTypesComeFromConstantsOrPrefixSuffixPairs() {
        assertThat(ConsumerContract.isHandled("OrderCreated", Set.of("OrderCreated"))).isTrue();
        assertThat(ConsumerContract.isHandled("VendorApproved", Set.of("Vendor", "Approved"))).isTrue();
        assertThat(ConsumerContract.isHandled("VendorApproved", Set.of("Vendor"))).isFalse();
        assertThat(ConsumerContract.EVENT_TYPE_SHAPE.matcher("OrderCreated").matches()).isTrue();
        assertThat(ConsumerContract.EVENT_TYPE_SHAPE.matcher("Vendor").matches()).isFalse();
        assertThat(ConsumerContract.EVENT_TYPE_SHAPE.matcher("GROCERY").matches()).isFalse();
        assertThat(ConsumerContract.EVENT_TYPE_SHAPE.matcher("order.events").matches()).isFalse();
    }

    /** Named like grocery-common's exception: the classification goes by name. */
    static class InvalidEventException extends RuntimeException {
    }
}
