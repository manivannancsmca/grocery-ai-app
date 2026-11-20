package com.grocery.common.autoconfigure;

import com.grocery.common.avro.AvroEventSchemas;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.StubSchemaRegistry;
import com.grocery.common.avro.TestEvents;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.OriginalValueDeadLetterPublishingRecoverer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.annotation.KafkaListener;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.grocery.common.avro.TestEvents.JSON;
import static com.grocery.common.avro.TestEvents.PAYMENT_CAPTURED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole consumer path against a real broker: Spring Boot's listener container with grocery-common's
 * auto-configuration, Avro and legacy JSON values, a stub Schema Registry that can fail, and the DLTs.
 */
@Testcontainers
class AvroKafkaConsumerIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");

    static final String AVRO = "it-avro.events";
    static final String LEGACY = "it-legacy.events";
    static final String POISON = "it-poison.events";
    static final String REJECTED = "it-rejected.events";
    static final String UNKNOWN = "it-unknown.events";
    static final String OUTAGE = "it-outage.events";
    static final List<String> TOPICS = List.of(AVRO, LEGACY, POISON, REJECTED, UNKNOWN, OUTAGE);

    static StubSchemaRegistry registry;

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final Schema schema = new AvroEventSchemas().require(TestEvents.TOPIC, PAYMENT_CAPTURED, 1);

    /** A grocery listener as services write them: ConsumerRecord<String, String> + EventEnvelope. */
    public static class Listener {
        final BlockingQueue<ConsumerRecord<String, String>> received = new LinkedBlockingQueue<>();

        @KafkaListener(id = "it-listener", groupId = "it-listener", topics = {AVRO, LEGACY, POISON, REJECTED, UNKNOWN, OUTAGE})
        public void on(ConsumerRecord<String, String> record) {
            EventEnvelope.from(record);
            if (record.topic().equals(REJECTED)) {
                throw new InvalidEventException("rejected by the listener");
            }
            received.add(record);
        }
    }

    @BeforeAll
    static void topicsAndRegistry() throws Exception {
        registry = StubSchemaRegistry.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(Stream.concat(TOPICS.stream(), TOPICS.stream().map(t -> t + ".DLT"))
                    .map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get(60, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stopRegistry() {
        registry.close();
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, GroceryAvroAutoConfiguration.class,
                    GroceryKafkaAutoConfiguration.class))
            .withBean(Listener.class);

    @Test
    void listenersSeeJsonRetryOutagesAndDeadLetterTheOriginalBytes() {
        runner.withPropertyValues(
                "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "grocery.events.avro.schema-registry-url=" + registry.url(),
                "grocery.events.avro.client.max.retries=0").run(context -> {
            Listener listener = context.getBean(Listener.class);
            registry.register(11, schema).register(13, schema);
            TestEvents.PaymentCaptured event = TestEvents.paymentCaptured();
            byte[] avro = TestEvents.wireFormat(11, schema, record(event));

            try (KafkaProducer<String, byte[]> producer = producer();
                 KafkaConsumer<String, byte[]> deadLetters = deadLetterConsumer()) {

                // 1. Avro -> the same JSON the producer wrote, read by the listener's own DTO
                send(producer, AVRO, avro);
                ConsumerRecord<String, String> got = next(listener, AVRO);
                assertThat(converter.readExact(got.value())).isEqualTo(converter.readExact(JSON.writeValueAsString(event)));
                assertThat(EventEnvelope.from(got).payloadAs(JSON, TestEvents.PaymentCaptured.class)).isEqualTo(event);

                // 2. legacy JSON (published before the switch, or replayed) passes through unchanged
                String legacy = JSON.writeValueAsString(TestEvents.paymentCapturedWithNulls());
                send(producer, LEGACY, legacy.getBytes(StandardCharsets.UTF_8));
                assertThat(next(listener, LEGACY).value()).isEqualTo(legacy);

                // 3. corrupt Avro: poison -> DLT at once, with the original bytes and without the helper header
                byte[] corrupt = Arrays.copyOf(avro, 12);
                send(producer, POISON, corrupt);
                ConsumerRecord<String, byte[]> dead = deadLetter(deadLetters, POISON + ".DLT");
                assertThat(dead.value()).isEqualTo(corrupt);
                assertThat(dead.key()).isEqualTo("order-1");
                assertCleanDeadLetter(dead, POISON);
                assertThat(header(dead, "kafka_dlt-exception-stacktrace")).contains("Corrupt Avro value");

                // 4. the listener rejects a valid Avro record: the DLT gets the Avro bytes, not the decoded JSON
                byte[] avro2 = TestEvents.wireFormat(11, schema, record(TestEvents.paymentCaptured()));
                send(producer, REJECTED, avro2);
                dead = deadLetter(deadLetters, REJECTED + ".DLT");
                assertThat(dead.value()).isEqualTo(avro2);
                assertCleanDeadLetter(dead, REJECTED);
                assertThat(header(dead, "kafka_dlt-exception-stacktrace"))
                        .contains(InvalidEventException.class.getName() + ": rejected by the listener");

                // 5. a schema id the registry does not know: poison -> DLT at once
                byte[] unknown = TestEvents.wireFormat(999, schema, record(event));
                send(producer, UNKNOWN, unknown);
                assertThat(deadLetter(deadLetters, UNKNOWN + ".DLT").value()).isEqualTo(unknown);

                // 6. registry outage (503, twice): the same record is retried, then delivered; nothing dead-lettered
                registry.failNext(2, 503);
                int before = registry.requests();
                byte[] avro3 = TestEvents.wireFormat(13, schema, record(event));
                send(producer, OUTAGE, avro3);
                assertThat(converter.readExact(next(listener, OUTAGE).value()))
                        .isEqualTo(converter.readExact(JSON.writeValueAsString(event)));
                assertThat(registry.requests() - before).isGreaterThanOrEqualTo(3);
                assertThat(deadLetters(deadLetters, Duration.ofSeconds(3))).extracting(ConsumerRecord::topic)
                        .doesNotContain(OUTAGE + ".DLT");
                assertThat(listener.received).isEmpty();
            }
        });
    }

    private GenericRecord record(TestEvents.PaymentCaptured event) {
        return converter.toRecord(converter.readExact(JSON.writeValueAsString(event)), schema);
    }

    private static void assertCleanDeadLetter(ConsumerRecord<String, byte[]> dead, String sourceTopic) {
        assertThat(dead.headers().lastHeader(OriginalValueDeadLetterPublishingRecoverer.ORIGINAL_VALUE_HEADER)).isNull();
        assertThat(dead.headers().lastHeader("springDeserializerExceptionValue")).isNull();
        assertThat(header(dead, "eventId")).isNotNull();                               // original headers kept
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(sourceTopic);
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).isNotNull();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static ConsumerRecord<String, String> next(Listener listener, String topic) throws InterruptedException {
        ConsumerRecord<String, String> record = listener.received.poll(60, TimeUnit.SECONDS);
        assertThat(record).as("record from " + topic).isNotNull();
        assertThat(record.topic()).isEqualTo(topic);
        return record;
    }

    private final List<ConsumerRecord<String, byte[]>> deadLetterBuffer = new ArrayList<>();

    private ConsumerRecord<String, byte[]> deadLetter(KafkaConsumer<String, byte[]> consumer, String topic) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : deadLetterBuffer) {
                if (record.topic().equals(topic)) {
                    deadLetterBuffer.remove(record);
                    return record;
                }
            }
            consumer.poll(Duration.ofMillis(500)).forEach(deadLetterBuffer::add);
        }
        throw new AssertionError("Nothing on " + topic);
    }

    private List<ConsumerRecord<String, byte[]>> deadLetters(KafkaConsumer<String, byte[]> consumer, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(deadLetterBuffer::add);
        }
        return deadLetterBuffer;
    }

    private static void send(KafkaProducer<String, byte[]> producer, String topic, byte[] value) throws Exception {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, "order-1", value);
        record.headers().add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", PAYMENT_CAPTURED.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(30, TimeUnit.SECONDS);
    }

    private static KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new ByteArraySerializer());
    }

    private static KafkaConsumer<String, byte[]> deadLetterConsumer() {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-dlt-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(TOPICS.stream().map(t -> t + ".DLT").toList());
        return consumer;
    }
}
