package com.grocery.cart;

import com.grocery.cart.config.CartProperties;
import com.grocery.cart.event.OrderEventsListener;
import com.grocery.cart.model.ItemType;
import com.grocery.cart.model.Store;
import com.grocery.cart.model.StoreType;
import com.grocery.cart.model.StoredCart;
import com.grocery.cart.model.StoredLine;
import com.grocery.cart.service.CartService;
import com.grocery.cart.store.CartStore;
import com.grocery.cart.store.CartStore.VersionedCart;
import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.common.kafka.TransientFailures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.grocery.cart.OrderEventRecords.newEventId;
import static com.grocery.cart.OrderEventRecords.orderCreated;
import static com.grocery.cart.OrderEventRecords.orderCreatedPayload;
import static com.grocery.cart.OrderEventRecords.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order.events → OrderCreated empties the cart the order was placed from, only if it has not changed since
 * (contract §6 "Cart"). The listener is called directly with records as Kafka delivers them.
 */
class OrderCreatedIntegrationTest extends AbstractIntegrationTest {

    private static final Instant PLACED = Instant.parse("2026-11-11T10:15:30.120Z");

    @Autowired
    OrderEventsListener listener;
    @Autowired
    CartStore store;
    @Autowired
    CartProperties properties;
    @Autowired
    Clock clock;
    @Autowired
    KafkaListenerEndpointRegistry kafkaListeners;
    @Autowired
    AvroEventEncoder avroEncoder;
    @Autowired
    JsonAvroConverter avroConverter;
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    private final Store greenBasket = new Store(UUID.randomUUID(), StoreType.GROCERY, "Green Basket");

    private StoredCart cartAt(Instant updatedAt) {
        StoredLine line = new StoredLine(UUID.randomUUID(), ItemType.PRODUCT, 2, new BigDecimal("90.00"), "Rice",
                "1 kg", null, "VEG", updatedAt);
        return StoredCart.EMPTY.withLine(greenBasket, line, updatedAt);
    }

    /** A customer whose stored cart (version 1) was last changed at {@code updatedAt}. */
    private UUID customerWithCartAt(Instant updatedAt) {
        UUID customerId = UUID.randomUUID();
        assertThat(store.compareAndSet(customerId, 0, cartAt(updatedAt))).isTrue();
        return customerId;
    }

    private ConsumerRecord<String, String> created(UUID customerId, String cartUpdatedAt) {
        return orderCreated(json, newEventId(), customerId, cartUpdatedAt);
    }

    /**
     * An OrderCreated as it arrives since ADR-0011: the JSON payload encoded to Avro by grocery-common's encoder (what
     * order-service's outbox stores in payload_avro, with the real schema from grocery-event-schemas) and decoded by
     * the value deserializer this service's consumer factory hands every listener container.
     */
    private ConsumerRecord<String, String> createdViaAvro(UUID customerId, String cartUpdatedAt) {
        UUID orderId = UUID.randomUUID();
        String payload = json.writeValueAsString(orderCreatedPayload(orderId, customerId, cartUpdatedAt));
        byte[] wire = avroEncoder.encode("order.events", "OrderCreated", 1, avroConverter.readExact(payload));
        assertThat(AvroEventDeserializer.isAvro(wire)).isTrue();
        Deserializer<?> values = consumerFactory.getValueDeserializer();
        assertThat(values).isInstanceOf(ErrorHandlingDeserializer.class);
        Headers headers = new RecordHeaders();
        Object value = values.deserialize("order.events", headers, wire);
        assertThat(headers.lastHeader("springDeserializerExceptionValue")).as("deserialization failure").isNull();
        assertThat(value).isInstanceOf(String.class);
        return record("OrderCreated", newEventId(), orderId.toString(), (String) value);
    }

    @Test
    void theListenerConsumesOrderEventsWithGroceryCommonsErrorHandler() {
        MessageListenerContainer container = kafkaListeners.getListenerContainer("cart-order-events");
        assertThat(container).isNotNull();
        assertThat(container.getGroupId()).isEqualTo("cart-service.order-events");
        assertThat(container.getContainerProperties().getTopics()).containsExactly("order.events");
        assertThat(((AbstractMessageListenerContainer<?, ?>) container).getCommonErrorHandler())
                .isInstanceOf(DefaultErrorHandler.class);
        assertThat(container.isRunning()).isFalse();                 // tests: spring.kafka.listener.auto-startup=false
    }

    @Test
    void theCartTheOrderWasPlacedFromIsEmptiedOnce() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID productId = product(vendor("Green Basket"), "Basmati Rice", "120.00", "90.00", 50);
        addProduct(customerId, productId, 2).andExpect(status().isOk());
        address(customerId, 2.0, true);
        // order-service copies the preview's updatedAt into OrderCreated.cartUpdatedAt, as cart-service wrote it
        JsonNode preview = body(preview(customerId, null).andExpect(status().isOk()));
        String cartUpdatedAt = preview.get("updatedAt").asString();
        assertThat(Instant.parse(cartUpdatedAt)).isEqualTo(store.load(customerId).cart().updatedAt());
        ConsumerRecord<String, String> event = created(customerId, cartUpdatedAt);

        listener.on(event);

        VersionedCart after = store.load(customerId);
        assertThat(after.cart().isEmpty()).isTrue();
        assertThat(after.cart().store()).isNull();
        assertThat(after.version()).isEqualTo(2);                    // one write: the clear
        assertThat(after.cart().updatedAt()).isAfter(Instant.parse(cartUpdatedAt));
        assertThat(ttlSeconds(customerId)).isPositive();             // stored empty, like DELETE /api/cart
        JsonNode cart = body(getCart(customerId).andExpect(status().isOk()));
        assertThat(cart.get("lines")).isEmpty();
        assertThat(cart.get("store").isNull()).isTrue();

        // Redelivered (no processed_event table: idempotent by nature): nothing is written again
        listener.on(event);
        assertThat(store.load(customerId).version()).isEqualTo(2);
    }

    @Test
    void anOrderCreatedThatTravelledAsAvroEmptiesTheCart() throws Exception {
        // the preview's updatedAt -> order-service's outbox (timestamp-micros) -> rendered back with Instant.toString()
        UUID customerId = UUID.randomUUID();
        UUID productId = product(vendor("Green Basket"), "Basmati Rice", "120.00", "90.00", 50);
        addProduct(customerId, productId, 2).andExpect(status().isOk());
        String cartUpdatedAt = body(preview(customerId, null).andExpect(status().isOk())).get("updatedAt").asString();
        ConsumerRecord<String, String> event = createdViaAvro(customerId, cartUpdatedAt);
        JsonNode decoded = json.readTree(event.value());
        assertThat(Instant.parse(decoded.get("cartUpdatedAt").asString())).isEqualTo(Instant.parse(cartUpdatedAt));
        assertThat(decoded.get("customerId").asString()).isEqualTo(customerId.toString());

        listener.on(event);

        VersionedCart after = store.load(customerId);
        assertThat(after.cart().isEmpty()).isTrue();
        assertThat(after.version()).isEqualTo(2);                    // one write: the clear

        // a cart changed at a whole second: Avro renders "...:30Z" (no fraction), still the same cart version
        UUID wholeSecond = customerWithCartAt(Instant.parse("2026-11-11T10:15:30Z"));
        listener.on(createdViaAvro(wholeSecond, "2026-11-11T10:15:30.000Z"));
        assertThat(store.load(wholeSecond).cart().isEmpty()).isTrue();
        // digits below a millisecond survive timestamp-micros and do not matter
        UUID micros = customerWithCartAt(PLACED);
        listener.on(createdViaAvro(micros, "2026-11-11T10:15:30.120456Z"));
        assertThat(store.load(micros).cart().isEmpty()).isTrue();
        // one millisecond off is still another cart version, and no cartUpdatedAt (Avro null) keeps the cart
        UUID changed = customerWithCartAt(PLACED);
        listener.on(createdViaAvro(changed, "2026-11-11T10:15:30.121Z"));
        listener.on(createdViaAvro(changed, null));
        assertThat(store.load(changed).version()).isEqualTo(1);
        assertThat(store.load(changed).cart().isEmpty()).isFalse();
    }

    @Test
    void aCartChangedAfterTheOrderWasPlacedIsKept() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 50);
        UUID dal = product(vendorId, "Toor Dal", "160.00", "140.00", 50);
        addProduct(customerId, rice, 2).andExpect(status().isOk());
        String cartUpdatedAt = body(preview(customerId, null).andExpect(status().isOk())).get("updatedAt").asString();
        Thread.sleep(5);                                             // the next edit gets a later updatedAt
        addProduct(customerId, dal, 1).andExpect(status().isOk());   // e.g. another tab, after placing the order
        long version = store.load(customerId).version();

        listener.on(created(customerId, cartUpdatedAt));

        VersionedCart after = store.load(customerId);
        assertThat(after.version()).isEqualTo(version);
        assertThat(after.cart().itemIds()).containsExactly(rice, dal);
    }

    @Test
    void updatedAtIsComparedAtTheCartsMillisecondPrecision() {
        // Instant.toString / Jackson write a whole second without a fraction: the stored text is "…:30Z"
        Instant wholeSecond = Instant.parse("2026-11-11T10:15:30Z");
        UUID a = customerWithCartAt(wholeSecond);
        listener.on(created(a, "2026-11-11T10:15:30.000Z"));
        assertThat(store.load(a).cart().isEmpty()).isTrue();

        // the exact representation the preview returns
        UUID b = customerWithCartAt(PLACED);
        listener.on(created(b, "2026-11-11T10:15:30.120Z"));
        assertThat(store.load(b).cart().isEmpty()).isTrue();

        // digits below a millisecond (e.g. after a round trip through DATETIME(6)) do not matter
        UUID c = customerWithCartAt(PLACED);
        listener.on(created(c, "2026-11-11T10:15:30.120000Z"));
        assertThat(store.load(c).cart().isEmpty()).isTrue();

        // one millisecond off is another cart version
        UUID d = customerWithCartAt(PLACED);
        listener.on(created(d, "2026-11-11T10:15:30.121Z"));
        listener.on(created(d, "2026-11-11T10:15:30.119Z"));
        assertThat(store.load(d).version()).isEqualTo(1);
        assertThat(store.load(d).cart().isEmpty()).isFalse();
    }

    @Test
    void aMissingOrAlreadyEmptyCartIsLeftAlone() throws Exception {
        UUID nobody = UUID.randomUUID();
        listener.on(created(nobody, PLACED.toString()));
        assertThat(redis.hasKey(CartStore.key(nobody))).isFalse();   // no key is created

        UUID customerId = customerWithCartAt(PLACED);
        clearCart(customerId).andExpect(status().isNoContent());     // emptied by the customer
        VersionedCart emptied = store.load(customerId);
        listener.on(created(customerId, emptied.cart().updatedAt().toString()));
        listener.on(created(customerId, PLACED.toString()));
        assertThat(store.load(customerId).version()).isEqualTo(emptied.version());

        // An event without cartUpdatedAt cannot prove the cart is unchanged: it is kept
        UUID kept = customerWithCartAt(PLACED);
        listener.on(created(kept, null));
        assertThat(store.load(kept).cart().isEmpty()).isFalse();
    }

    @Test
    void otherOrderEventsAreIgnored() {
        UUID customerId = customerWithCartAt(PLACED);
        // even with a payload that would match an OrderCreated, and with payloads this service cannot read
        String matching = json.writeValueAsString(orderCreatedPayload(UUID.randomUUID(), customerId, PLACED.toString()));
        for (String type : new String[] {"OrderAwaitingPayment", "OrderConfirmed", "OrderRejected", "OrderCancelled"}) {
            listener.on(record(type, newEventId(), UUID.randomUUID().toString(), matching));
            listener.on(record(type, newEventId(), UUID.randomUUID().toString(), "not json"));
        }
        assertThat(store.load(customerId).version()).isEqualTo(1);
        assertThat(store.load(customerId).cart().isEmpty()).isFalse();
    }

    @Test
    void malformedEventsAreRejected() {
        UUID customerId = customerWithCartAt(PLACED);
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> listener.on(record("OrderCreated", newEventId(), key, "not json")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> listener.on(orderCreated(json, newEventId(), null, PLACED.toString())))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> listener.on(record("OrderCreated", newEventId(), key,
                json.writeValueAsString(Map.of("customerId", customerId.toString(), "cartUpdatedAt", PLACED.toString())))))
                .isInstanceOf(InvalidEventException.class);                       // no orderId
        assertThatThrownBy(() -> listener.on(created(customerId, "yesterday")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> listener.on(record("OrderCreated", newEventId(), key,
                json.writeValueAsString(Map.of("orderId", key, "customerId", "not-a-uuid", "cartUpdatedAt",
                        PLACED.toString())))))
                .isInstanceOf(InvalidEventException.class);
        ConsumerRecord<String, String> noHeaders = new ConsumerRecord<>("order.events", 0, 0L, key,
                json.writeValueAsString(orderCreatedPayload(UUID.randomUUID(), customerId, PLACED.toString())));
        assertThatThrownBy(() -> listener.on(noHeaders)).isInstanceOf(InvalidEventException.class);
        assertThat(store.load(customerId).version()).isEqualTo(1);
    }

    @Test
    void anEditRacingWithTheClearIsNeverLost() {
        UUID customerId = customerWithCartAt(PLACED);
        StoredLine added = new StoredLine(UUID.randomUUID(), ItemType.PRODUCT, 1, new BigDecimal("40.00"), "Salt",
                "1 kg", null, "VEG", PLACED.plusSeconds(1));
        AtomicBoolean raced = new AtomicBoolean();
        // The customer adds an item after the clear read the (still unchanged) cart, before the clear is written
        CartStore racing = new CartStore(redis, json, properties) {
            @Override
            public boolean compareAndSet(UUID id, long expectedVersion, StoredCart cart) {
                if (raced.compareAndSet(false, true)) {
                    VersionedCart current = store.load(id);
                    assertThat(store.compareAndSet(id, current.version(),
                            current.cart().withLine(greenBasket, added, PLACED.plusSeconds(1)))).isTrue();
                }
                return super.compareAndSet(id, expectedVersion, cart);
            }
        };
        CartService carts = new CartService(racing, null, null, null, null, null, null, properties, clock);

        assertThat(carts.clearIfUnchangedSince(customerId, PLACED)).isFalse();

        assertThat(raced).isTrue();
        VersionedCart after = store.load(customerId);
        assertThat(after.version()).isEqualTo(2);                    // only the customer's edit was written
        assertThat(after.cart().lines()).hasSize(2);
        assertThat(after.cart().line(added.itemId())).isEqualTo(added);
    }

    @Test
    void aCartThatNeverStopsChangingIsRetriedLater() {
        UUID customerId = customerWithCartAt(PLACED);
        CartStore alwaysOvertaken = new CartStore(redis, json, properties) {
            @Override
            public boolean compareAndSet(UUID id, long expectedVersion, StoredCart cart) {
                return false;                                        // someone else wrote first, every time
            }
        };
        OrderEventsListener busy = new OrderEventsListener(
                new CartService(alwaysOvertaken, null, null, null, null, null, null, properties, clock), json);

        assertThatThrownBy(() -> busy.on(created(customerId, PLACED.toString())))
                .isInstanceOfSatisfying(TransientEventProcessingException.class,
                        e -> assertThat(TransientFailures.isTransient(e)).isTrue());
        assertThat(store.load(customerId).cart().isEmpty()).isFalse();
    }
}
