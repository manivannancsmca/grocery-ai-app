package com.grocery.common.deadletter;

import com.grocery.common.autoconfigure.GroceryAvroAutoConfiguration;
import com.grocery.common.autoconfigure.GroceryDeadLetterAutoConfiguration;
import com.grocery.common.autoconfigure.GroceryKafkaAutoConfiguration;
import com.grocery.common.avro.AvroEventSchemas;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.StubSchemaRegistry;
import com.grocery.common.avro.TestEvents;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.web.GlobalExceptionHandler;
import com.grocery.common.web.PageResponse;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.grocery.common.avro.TestEvents.JSON;
import static com.grocery.common.avro.TestEvents.PAYMENT_CAPTURED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dead letters end to end against a real broker: a listener dead-letters records (grocery-common's error handler and
 * Avro deserializer), the service lists them (only its own consumer group), replays them in process, discards them,
 * and a second instance of the same service (another application context: a replica) sees every action through
 * {@code _grocery.dead-letter-actions}.
 */
@Testcontainers
class DeadLetterIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");

    static final String TOPIC = "dl-it.events";
    static final String DLT = TOPIC + ".DLT";
    static final String ACTIONS = "_grocery.dead-letter-actions";
    static final String GROUP = "dl-it.consumer";
    static final String OTHER_GROUP = "other-service.consumer";
    static final String CORRELATION_ID = "corr-dl-it-0001";

    static StubSchemaRegistry registry;

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final Schema schema = new AvroEventSchemas().require(TestEvents.TOPIC, PAYMENT_CAPTURED, 1);

    /** A grocery listener: rejects poison, and (to simulate a bug fixed later) the event ids in {@link #rejected}. */
    public static class Listener {
        final BlockingQueue<ConsumerRecord<String, String>> received = new LinkedBlockingQueue<>();
        final List<String> correlationIds = new CopyOnWriteArrayList<>();
        final Set<String> rejected = ConcurrentHashMap.newKeySet();

        @KafkaListener(id = "dl-it-listener", groupId = GROUP, topics = TOPIC)
        public void on(ConsumerRecord<String, String> record) {
            EventEnvelope event = EventEnvelope.from(record);
            correlationIds.add(String.valueOf(MDC.get("correlationId")));
            if ("Poison".equals(event.eventType())) {
                throw new InvalidEventException("poison payload " + event.eventId());
            }
            if (rejected.contains(event.eventId())) {
                throw new InvalidEventException("rejected " + event.eventId());
            }
            received.add(record);
        }
    }

    @BeforeAll
    static void topicsAndRegistry() throws Exception {
        registry = StubSchemaRegistry.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DLT, 1, (short) 1),
                    new NewTopic(ACTIONS, 1, (short) 1).configs(Map.of("cleanup.policy", "compact"))))
                    .all().get(60, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stopRegistry() {
        registry.close();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, GroceryAvroAutoConfiguration.class,
                    GroceryKafkaAutoConfiguration.class, GroceryDeadLetterAutoConfiguration.class))
            .withBean(Listener.class)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                    "spring.kafka.consumer.auto-offset-reset=earliest",
                    "grocery.events.avro.schema-registry-url=" + registry.url(),
                    "grocery.events.avro.client.max.retries=0",
                    "grocery.dead-letters.poll-timeout=200ms");

    @Test
    void deadLettersAreListedReplayedAndDiscardedAndEveryInstanceSeesTheActions() {
        registry.register(21, schema);
        runner.run(a -> runner
                // a second replica: its listener container does not consume (so instance A's handles every record),
                // but it reads the DLT and the actions like any instance
                .withPropertyValues("spring.kafka.listener.auto-startup=false", "grocery.dead-letters.auto-startup=true")
                .run(b -> {
                    Listener listener = a.getBean(Listener.class);
                    DeadLetterService serviceA = a.getBean(DeadLetterService.class);
                    DeadLetterService serviceB = b.getBean(DeadLetterService.class);

                    // 1. three records the listener cannot take, and another service's dead letter on the same DLT
                    String poisonId = UUID.randomUUID().toString();
                    String validId = UUID.randomUUID().toString();
                    String corruptId = UUID.randomUUID().toString();
                    TestEvents.PaymentCaptured event = TestEvents.paymentCaptured();
                    byte[] avro = TestEvents.wireFormat(21, schema, record(event));
                    listener.rejected.add(validId);
                    try (KafkaProducer<String, byte[]> producer = producer()) {
                        send(producer, TOPIC, poisonId, "Poison", "{\"bad\":true}".getBytes(StandardCharsets.UTF_8));
                        send(producer, TOPIC, validId, PAYMENT_CAPTURED, avro);
                        send(producer, TOPIC, corruptId, PAYMENT_CAPTURED, Arrays.copyOf(avro, 12));
                        long foreignOffset = foreignDeadLetter(producer);
                        String foreignId = DLT + ":0:" + foreignOffset;

                        List<DeadLetterView> views = await(() -> serviceA.list(null, null, null, 0, 100),
                                page -> page.totalElements() == 3).content();

                        // 2. the views
                        DeadLetterView poison = byEventId(views, poisonId);
                        assertThat(poison.id()).startsWith(DLT + ":0:");
                        assertThat(poison.dltTopic()).isEqualTo(DLT);
                        assertThat(poison.originalTopic()).isEqualTo(TOPIC);
                        assertThat(poison.originalPartition()).isZero();
                        assertThat(poison.consumerGroup()).isEqualTo(GROUP);
                        assertThat(poison.key()).isEqualTo("order-1");
                        assertThat(poison.eventType()).isEqualTo("Poison");
                        assertThat(poison.eventVersion()).isEqualTo(1);
                        assertThat(poison.occurredAt()).isEqualTo(java.time.Instant.parse("2026-11-11T10:05:00.250123Z"));
                        assertThat(poison.deadLetteredAt()).isNotNull();
                        assertThat(poison.exceptionClass()).isEqualTo(InvalidEventException.class.getName());
                        assertThat(poison.exceptionMessage()).isEqualTo("poison payload " + poisonId);
                        assertThat(poison.payloadPreview()).isEqualTo("{\"bad\":true}");
                        assertThat(poison.status()).isEqualTo(DeadLetterStatus.PENDING);
                        assertThat(poison.attempts()).isZero();

                        DeadLetterView valid = byEventId(views, validId);
                        assertThat(valid.exceptionMessage()).isEqualTo("rejected " + validId);
                        DeadLetterDetail validDetail = serviceA.get(valid.id());
                        assertThat(validDetail.payload())                       // Avro decoded to the producer's JSON
                                .isEqualTo(converter.readExact(JSON.writeValueAsString(event)));
                        assertThat(validDetail.stackTrace()).isNotBlank().hasSizeLessThanOrEqualTo(4096);

                        DeadLetterView corrupt = byEventId(views, corruptId);
                        assertThat(corrupt.exceptionClass()).isEqualTo(InvalidEventException.class.getName());
                        assertThat(corrupt.exceptionMessage()).startsWith("Corrupt Avro value on " + TOPIC);
                        assertThat(corrupt.payloadPreview()).isNull();
                        assertThat(serviceA.get(corrupt.id()).payload()).isNull();

                        // 3. only this service's consumer groups
                        assertThat(serviceA.list(null, OTHER_GROUP, null, 0, 100).totalElements()).isZero();
                        assertThat(serviceA.list(null, GROUP, PAYMENT_CAPTURED, 0, 100).totalElements()).isEqualTo(2);
                        assertThatThrownBy(() -> serviceA.get(foreignId)).isInstanceOfSatisfying(DeadLetterException.class,
                                e -> assertThat(e.errorCode()).isEqualTo("DEAD_LETTER_NOT_FOUND"));
                        assertThatThrownBy(() -> serviceB.get(foreignId)).isInstanceOf(DeadLetterException.class);

                        // 4. poison: the replay fails again, an operator discards it, a replay is then refused
                        DeadLetterView failed = serviceA.replay(poison.id(), "ops-a");
                        assertThat(failed.status()).isEqualTo(DeadLetterStatus.REPLAY_FAILED);
                        assertThat(failed.attempts()).isEqualTo(1);
                        assertThat(failed.lastError()).isEqualTo(InvalidEventException.class.getName()
                                + ": poison payload " + poisonId);
                        assertThat(failed.lastActionBy()).isEqualTo("ops-a");
                        assertThat(MDC.get("correlationId")).isNull();          // restored after the replay
                        DeadLetterView discarded = serviceA.discard(poison.id(), "Known bad producer data", "ops-a");
                        assertThat(discarded.status()).isEqualTo(DeadLetterStatus.DISCARDED);
                        assertThat(discarded.attempts()).isEqualTo(1);
                        assertClosed(() -> serviceA.replay(poison.id(), "ops-a"), "DISCARDED");
                        // ... and on the other instance (an action written by A, read by B)
                        DeadLetterView seenByB = serviceB.get(poison.id()).view();
                        assertThat(seenByB.status()).isEqualTo(DeadLetterStatus.DISCARDED);
                        assertThat(seenByB.attempts()).isEqualTo(1);
                        assertThat(seenByB.lastActionBy()).isEqualTo("ops-a");
                        assertClosed(() -> serviceB.replay(poison.id(), "ops-b"), "DISCARDED");
                        assertClosed(() -> serviceB.discard(poison.id(), "again", "ops-b"), "DISCARDED");

                        // 5. the listener is fixed: the replay hands the original record to it, once
                        listener.rejected.clear();
                        listener.correlationIds.clear();
                        DeadLetterView replayed = serviceA.replay(valid.id(), "ops-a");
                        assertThat(replayed.status()).isEqualTo(DeadLetterStatus.REPLAYED);
                        assertThat(replayed.attempts()).isEqualTo(1);
                        assertThat(replayed.lastError()).isNull();
                        ConsumerRecord<String, String> got = listener.received.poll(10, TimeUnit.SECONDS);
                        assertThat(got).isNotNull();
                        assertThat(got.topic()).isEqualTo(TOPIC);
                        assertThat(got.partition()).isZero();
                        assertThat(got.offset()).isEqualTo(valid.originalOffset());
                        assertThat(got.key()).isEqualTo("order-1");
                        assertThat(EventEnvelope.from(got).eventId()).isEqualTo(validId);
                        assertThat(converter.readExact(got.value())).isEqualTo(converter.readExact(JSON.writeValueAsString(event)));
                        for (Header header : got.headers()) {
                            assertThat(header.key()).doesNotStartWith("kafka_dlt-");
                        }
                        assertThat(listener.correlationIds).containsExactly(CORRELATION_ID);

                        // replay twice -> 409, on the other instance too; the listener ran once
                        assertClosed(() -> serviceB.replay(valid.id(), "ops-b"), "REPLAYED");
                        assertClosed(() -> serviceA.replay(valid.id(), "ops-a"), "REPLAYED");
                        assertThat(listener.received).isEmpty();
                        assertThat(b.getBean(Listener.class).received).isEmpty();

                        // 6. an action written by B (the corrupt value cannot be decoded) is seen by A
                        DeadLetterView corruptReplay = serviceB.replay(corrupt.id(), "ops-b");
                        assertThat(corruptReplay.status()).isEqualTo(DeadLetterStatus.REPLAY_FAILED);
                        assertThat(corruptReplay.lastError()).startsWith(InvalidEventException.class.getName()
                                + ": Corrupt Avro value on " + TOPIC);
                        DeadLetterView corruptOnA = serviceA.get(corrupt.id()).view();
                        assertThat(corruptOnA.status()).isEqualTo(DeadLetterStatus.REPLAY_FAILED);
                        assertThat(corruptOnA.lastActionBy()).isEqualTo("ops-b");
                        assertThat(serviceA.list(Set.of(DeadLetterStatus.REPLAY_FAILED), null, null, 0, 10).content())
                                .extracting(DeadLetterView::id).containsExactly(corrupt.id());

                        // 7. the HTTP layer of instance B
                        SecurityContextHolder.getContext().setAuthentication(
                                new TestingAuthenticationToken("ops-b", null, "ROLE_OPS_ADMIN"));
                        MockMvc mvc = MockMvcBuilders.standaloneSetup(b.getBean(DeadLetterController.class))
                                .setControllerAdvice(new GlobalExceptionHandler()).build();
                        mvc.perform(post("/api/admin/dead-letters/" + valid.id() + "/replay"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.errorCode").value("DEAD_LETTER_CLOSED"))
                                .andExpect(jsonPath("$.currentStatus").value("REPLAYED"));
                        mvc.perform(get("/api/admin/dead-letters").param("status", "DISCARDED"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].id").value(poison.id()))
                                .andExpect(jsonPath("$.totalElements").value(1));
                    }
                }));
    }

    private static void assertClosed(Runnable action, String currentStatus) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DeadLetterException.class, e -> {
            assertThat(e.errorCode()).isEqualTo("DEAD_LETTER_CLOSED");
            assertThat(e.properties()).isEqualTo(Map.of("currentStatus", currentStatus));
        });
    }

    private static DeadLetterView byEventId(List<DeadLetterView> views, String eventId) {
        return views.stream().filter(v -> eventId.equals(v.eventId())).findFirst()
                .orElseThrow(() -> new AssertionError("No dead letter for event " + eventId + " in " + views));
    }

    private static <T> T await(Supplier<T> query, Predicate<T> done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        T last = query.get();
        while (!done.test(last)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met in time; last: " + (last instanceof PageResponse<?> page
                        ? page.content() : last));
            }
            Thread.sleep(200);
            last = query.get();
        }
        return last;
    }

    private GenericRecord record(TestEvents.PaymentCaptured event) {
        return converter.toRecord(converter.readExact(JSON.writeValueAsString(event)), schema);
    }

    private static void send(KafkaProducer<String, byte[]> producer, String topic, String eventId, String eventType,
                             byte[] value) throws Exception {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, "order-1", value);
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("occurredAt", "1794391500250123".getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", CORRELATION_ID.getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(30, TimeUnit.SECONDS);
    }

    /** A dead letter of another service's consumer group on the same DLT. */
    private static long foreignDeadLetter(KafkaProducer<String, byte[]> producer) throws Exception {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(DLT, "order-9", "{}".getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", PAYMENT_CAPTURED.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, TOPIC.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(0).array());
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(99).array());
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, OTHER_GROUP.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.IllegalStateException".getBytes(StandardCharsets.UTF_8));
        return producer.send(record).get(30, TimeUnit.SECONDS).offset();
    }

    private static KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new ByteArraySerializer());
    }

    static Duration seconds(long seconds) {
        return Duration.ofSeconds(seconds);
    }
}
