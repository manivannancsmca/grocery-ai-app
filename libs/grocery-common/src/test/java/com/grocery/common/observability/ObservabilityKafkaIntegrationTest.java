package com.grocery.common.observability;

import com.grocery.common.autoconfigure.GroceryAvroAutoConfiguration;
import com.grocery.common.autoconfigure.GroceryDeadLetterAutoConfiguration;
import com.grocery.common.autoconfigure.GroceryKafkaAutoConfiguration;
import com.grocery.common.autoconfigure.GroceryObservabilityAutoConfiguration;
import com.grocery.common.deadletter.DeadLetterService;
import com.grocery.common.deadletter.DeadLetterStatus;
import com.grocery.common.deadletter.DeadLetterView;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.observability.GroceryEventMetrics.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.kafka.annotation.KafkaListener;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracing and the generic business metrics on the consumer side, against a real broker, with Spring Boot's
 * OpenTelemetry tracing and grocery-common's defaults: a record with a {@code traceparent} header is handled in a child
 * span of that trace, {@code grocery_events_consumed_total} counts APPLIED / DUPLICATE / FAILED,
 * {@code grocery_dead_letters_pending} follows the dead-letter index, and the in-process replay joins the record's trace.
 */
@Testcontainers
class ObservabilityKafkaIntegrationTest {

    static {
        TestTracing.init();
    }

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");

    static final String TOPIC = "obs-it.events";
    static final String GROUP = "obs-it.consumer";

    /** A grocery listener: guarded like services do it (in memory here, reporting like JdbcProcessedEventGuard). */
    public static class Listener {
        record Seen(String eventId, String eventType, String traceId, String spanId, String parentId) {
        }

        final BlockingQueue<Seen> seen = new LinkedBlockingQueue<>();
        final Set<String> processed = ConcurrentHashMap.newKeySet();
        final Set<String> rejected = ConcurrentHashMap.newKeySet();
        private final Tracer tracer;

        public Listener(Tracer tracer) {
            this.tracer = tracer;
        }

        @KafkaListener(id = "obs-it-listener", groupId = GROUP, topics = TOPIC)
        public void on(ConsumerRecord<String, String> record) {
            EventEnvelope event = EventEnvelope.from(record);
            if (rejected.contains(event.eventId())) {
                throw new InvalidEventException("rejected " + event.eventId());
            }
            ConsumedEvents.report(processed.add(event.eventId()));
            TraceContext context = tracer.currentTraceContext().context();
            seen.add(new Seen(event.eventId(), event.eventType(), context == null ? null : context.traceId(),
                    context == null ? null : context.spanId(), context == null ? null : context.parentId()));
        }
    }

    @BeforeAll
    static void topics() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(TOPIC + ".DLT", 1, (short) 1),
                    new NewTopic("_grocery.dead-letter-actions", 1, (short) 1).configs(Map.of("cleanup.policy", "compact"))))
                    .all().get(60, TimeUnit.SECONDS);
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, GroceryAvroAutoConfiguration.class,
                    GroceryKafkaAutoConfiguration.class, GroceryDeadLetterAutoConfiguration.class,
                    GroceryObservabilityAutoConfiguration.class, ObservationAutoConfiguration.class,
                    OpenTelemetrySdkAutoConfiguration.class, OpenTelemetryTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class, MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class))
            // what SpringApplication does with grocery-common's EnvironmentPostProcessor
            .withInitializer(context -> new GroceryObservabilityEnvironmentPostProcessor()
                    .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
            .withBean("spanExporter", InMemorySpanExporter.class, InMemorySpanExporter::create)
            .withBean(Listener.class)
            .withPropertyValues(
                    "spring.application.name=obs-it",
                    "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                    "spring.kafka.consumer.auto-offset-reset=earliest",
                    "grocery.events.avro.enabled=false",
                    "grocery.dead-letters.poll-timeout=200ms",
                    "management.tracing.export.otlp.enabled=false",
                    "management.opentelemetry.tracing.export.schedule-delay=100ms");

    @Test
    void consumedRecordsContinueTheirTraceAndAreCountedByOutcome() {
        runner.run(context -> {
            Listener listener = context.getBean(Listener.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            InMemorySpanExporter spans = context.getBean(InMemorySpanExporter.class);
            DeadLetterService deadLetters = context.getBean(DeadLetterService.class);
            assertThat(gauge(registry)).isZero();                  // registered at start, 0 without dead letters

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                    Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                    new StringSerializer(), new StringSerializer())) {

                // 1. an event published inside a trace (the outbox's traceparent column -> Debezium header)
                String traceId = hex(32);
                String parentSpanId = hex(16);
                String placed = UUID.randomUUID().toString();
                send(producer, placed, "OrderPlaced", "00-" + traceId + "-" + parentSpanId + "-01");
                Listener.Seen seen = next(listener);
                assertThat(seen.traceId()).isEqualTo(traceId);
                assertThat(seen.parentId()).isEqualTo(parentSpanId);
                assertThat(seen.spanId()).isNotEqualTo(parentSpanId);
                SpanData consumerSpan = awaitSpan(spans, seen.spanId());
                assertThat(consumerSpan.getTraceId()).isEqualTo(traceId);
                assertThat(consumerSpan.getParentSpanId()).isEqualTo(parentSpanId);
                assertThat(consumerSpan.getKind()).isEqualTo(SpanKind.CONSUMER);
                assertThat(consumerSpan.getParentSpanContext().isRemote()).isTrue();
                await(() -> consumed(registry, "OrderPlaced", Outcome.APPLIED) == 1);

                // 2. the same event again (at-least-once delivery): DUPLICATE
                send(producer, placed, "OrderPlaced", "00-" + traceId + "-" + parentSpanId + "-01");
                next(listener);
                await(() -> consumed(registry, "OrderPlaced", Outcome.DUPLICATE) == 1);
                assertThat(consumed(registry, "OrderPlaced", Outcome.APPLIED)).isOne();

                // 3. a poison record: dead-lettered at once -> FAILED once, and pending in the gauge
                String rejectedTrace = hex(32);
                String rejected = UUID.randomUUID().toString();
                listener.rejected.add(rejected);
                send(producer, rejected, "OrderRejected", "00-" + rejectedTrace + "-" + hex(16) + "-01");
                await(() -> consumed(registry, "OrderRejected", Outcome.FAILED) == 1);
                await(() -> gauge(registry) == 1);
                assertThat(consumed(registry, "OrderRejected", Outcome.APPLIED)).isZero();

                // 4. fixed and replayed in process: inside the record's trace, APPLIED, no longer pending
                listener.rejected.clear();
                String deadLetterId = deadLetters.list(null, GROUP, null, 0, 10).content().getFirst().id();
                DeadLetterView replayed = deadLetters.replay(deadLetterId, "ops-1");
                assertThat(replayed.status()).isEqualTo(DeadLetterStatus.REPLAYED);
                Listener.Seen replay = next(listener);
                assertThat(replay.eventId()).isEqualTo(rejected);
                assertThat(replay.traceId()).isEqualTo(rejectedTrace);
                SpanData replaySpan = awaitSpan(spans, replay.spanId());
                assertThat(replaySpan.getTraceId()).isEqualTo(rejectedTrace);
                assertThat(replaySpan.getKind()).isEqualTo(SpanKind.CONSUMER);
                assertThat(consumed(registry, "OrderRejected", Outcome.APPLIED)).isOne();
                assertThat(consumed(registry, "OrderRejected", Outcome.FAILED)).isOne();
                assertThat(gauge(registry)).isZero();

                // the common tags are on the business meters too
                Meter applied = registry.get(GroceryEventMetrics.EVENTS_CONSUMED).tag("outcome", "APPLIED")
                        .tag("event_type", "OrderPlaced").meter();
                assertThat(applied.getId().getTags()).contains(Tag.of("service", "obs-it"), Tag.of("env", "local"),
                        Tag.of("consumer_group", GROUP));
                assertThat(applied.getId().getTag("instance")).isNotBlank();
            }
        });
    }

    private static double consumed(MeterRegistry registry, String eventType, Outcome outcome) {
        Counter counter = registry.find(GroceryEventMetrics.EVENTS_CONSUMED)
                .tags("consumer_group", GROUP, "event_type", eventType, "outcome", outcome.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    private static double gauge(MeterRegistry registry) {
        Gauge gauge = registry.find(GroceryEventMetrics.DEAD_LETTERS_PENDING).tag("consumer_group", GROUP).gauge();
        assertThat(gauge).as("grocery_dead_letters_pending{consumer_group=%s}", GROUP).isNotNull();
        return gauge.value();
    }

    private static SpanData awaitSpan(InMemorySpanExporter spans, String spanId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            for (SpanData span : spans.getFinishedSpanItems()) {
                if (span.getSpanId().equals(spanId)) {
                    return span;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Span " + spanId + " not exported; exported: " + spans.getFinishedSpanItems());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met in time");
            }
            Thread.sleep(100);
        }
    }

    private static Listener.Seen next(Listener listener) throws InterruptedException {
        Listener.Seen seen = listener.seen.poll(60, TimeUnit.SECONDS);
        assertThat(seen).as("record handled by the listener").isNotNull();
        return seen;
    }

    private static String hex(int length) {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, length);
    }

    private static void send(KafkaProducer<String, String> producer, String eventId, String eventType,
                             String traceparent) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "order-1", "{\"orderId\":\"order-1\"}");
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(30, TimeUnit.SECONDS);
    }
}
