package com.grocery.common.observability;

import com.grocery.common.observability.GroceryEventMetrics.Outcome;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A service in miniature with grocery-common 0.9.0 and nothing else configured (no exposure, no tracing settings, no
 * collector running): {@code /actuator/prometheus} is exposed and public, every meter carries {@code service},
 * {@code env} and {@code instance}, the business meters are there, and the ECS JSON log lines carry {@code traceId},
 * {@code spanId} and {@code correlationId}. The OTLP exporter points at {@code http://localhost:4318}, where nothing
 * listens: exports fail in the background and nothing else notices.
 */
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
@SpringBootTest(classes = ObservabilityApplicationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=obs-test",
                "GROCERY_ENV=test",
                "logging.structured.format.console=ecs",
                "grocery.security.public-paths=/obs-test/**",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9/.well-known/jwks.json",
                "grocery.events.avro.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "management.opentelemetry.tracing.export.schedule-delay=200ms",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        })
class ObservabilityApplicationTest {

    static {
        TestTracing.init();
    }

    static final String GROUP = "obs-test.consumer";
    static final String CORRELATION_ID = "corr-obs-test-0001";
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(ObservabilityApplicationTest.class);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({Hello.class, Listener.class})
    static class App {
    }

    @RestController
    public static class Hello {
        @GetMapping("/obs-test/hello")
        String hello() {
            log.info("Hello from the controller");
            return "hello";
        }
    }

    /** Gives the service a consumer group (never started here), so the dead-letter gauge exists. */
    public static class Listener {
        @KafkaListener(id = "obs-test-listener", groupId = GROUP, topics = "obs-test.events")
        public void on(String value) {
        }
    }

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;
    @Autowired
    GroceryEventMetrics metrics;
    @Autowired
    Environment environment;
    @Autowired
    List<SpanExporter> spanExporters;

    private HttpResponse<String> get(String path, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void prometheusEndpointListsTheCommonTagsAndTheBusinessMeters(CapturedOutput output) throws Exception {
        metrics.published("order.events", "OrderPlaced");
        metrics.consumed(GROUP, "OrderPlaced", Outcome.APPLIED);
        assertThat(get("/obs-test/hello", CORRELATION_ID).statusCode()).isEqualTo(200);

        HttpResponse<String> response = get("/actuator/prometheus", null);         // no token: public
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        String env = "env=\"test\"";
        String instance = "instance=\"" + GroceryObservability.instance(environment) + "\"";
        List<String> lines = body.lines().toList();

        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("grocery_events_published_total{")
                .contains(env, instance, "event_type=\"OrderPlaced\"", "service=\"obs-test\"", "topic=\"order.events\"")
                .endsWith(" 1.0"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("grocery_events_consumed_total{")
                .contains("consumer_group=\"" + GROUP + "\"", env, instance, "event_type=\"OrderPlaced\"",
                        "outcome=\"APPLIED\"", "service=\"obs-test\"")
                .endsWith(" 1.0"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("grocery_dead_letters_pending{")
                .contains("consumer_group=\"" + GROUP + "\"", env, instance, "service=\"obs-test\"")
                .endsWith(" 0.0"));
        // Spring Boot's own meters carry the common tags too (HTTP server observation, JVM)
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("http_server_requests_seconds_count{")
                .contains(env, instance, "service=\"obs-test\"", "uri=\"/obs-test/hello\""));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("jvm_memory_used_bytes{").contains(env, instance, "service=\"obs-test\""));

        // explicit SLO buckets for http.server.requests (p95 via histogram_quantile), not a full percentiles histogram
        Set<Double> buckets = lines.stream()
                .filter(line -> line.startsWith("http_server_requests_seconds_bucket{")
                        && line.contains("uri=\"/obs-test/hello\""))
                .map(ObservabilityApplicationTest::le)
                .collect(Collectors.toSet());
        assertThat(buckets).containsExactlyInAnyOrder(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, Double.POSITIVE_INFINITY);
    }

    @Test
    void histogramBucketsCarryTheTraceIdAsExemplar() throws Exception {
        assertThat(get("/obs-test/hello", CORRELATION_ID).statusCode()).isEqualTo(200);

        // exemplars are part of the OpenMetrics format (what Prometheus asks for with exemplar storage on)
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .header("Accept", "application/openmetrics-text;version=1.0.0;charset=utf-8")
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/openmetrics-text"));
        assertThat(response.body().lines().toList()).anySatisfy(line -> assertThat(line)
                .startsWith("http_server_requests_seconds_bucket{")
                .contains("uri=\"/obs-test/hello\"")
                .containsPattern(" # \\{.*trace_id=\"[0-9a-f]{32}\".*}"));
    }

    @Test
    void tracesGoToTheLocalCollectorByDefaultAndEveryTraceIsSampled() {
        assertThat(spanExporters).anySatisfy(exporter -> assertThat(exporter)
                .isInstanceOf(OtlpHttpSpanExporter.class)
                .asString().contains("http://localhost:4318/v1/traces"));
        assertThat(environment.getProperty("management.tracing.sampling.probability", Double.class)).isEqualTo(1.0);
    }

    /** The {@code le} label of a Prometheus bucket line as a number. */
    private static double le(String line) {
        Matcher le = Pattern.compile("le=\"([^\"]+)\"").matcher(line);
        assertThat(le.find()).as(line).isTrue();
        return "+Inf".equals(le.group(1)) ? Double.POSITIVE_INFINITY : Double.parseDouble(le.group(1));
    }

    @Test
    void logLinesAreEcsJsonWithTraceIdSpanIdAndCorrelationId(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/obs-test/hello", CORRELATION_ID);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains(CORRELATION_ID);

        JsonNode line = awaitLogLine(output, "Hello from the controller");
        assertThat(line.path("traceId").asString()).matches("[0-9a-f]{32}");
        assertThat(line.path("spanId").asString()).matches("[0-9a-f]{16}");
        assertThat(line.path("correlationId").asString()).isEqualTo(CORRELATION_ID);
        assertThat(line.path("service").path("name").asString()).isEqualTo("obs-test");
        assertThat(line.path("service").path("environment").asString()).isEqualTo("test");
        assertThat(line.path("service").path("node").path("name").asString())
                .isEqualTo(GroceryObservability.instance(environment));
        assertThat(line.path("log").path("level").asString()).isEqualTo("INFO");
        assertThat(line.path("message").asString()).isEqualTo("Hello from the controller");
        assertThat(line.path("ecs").path("version").isMissingNode()).isFalse();

        // no collector at localhost:4318: the batch exports fail in the background, the service keeps answering
        Thread.sleep(1000);
        assertThat(get("/obs-test/hello", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    private static JsonNode awaitLogLine(CapturedOutput output, String message) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<JsonNode> line = output.getOut().lines()
                    .filter(text -> text.startsWith("{") && text.contains(message))
                    .map(JSON::readTree)
                    .filter(node -> message.equals(node.path("message").asString()))
                    .reduce((first, second) -> second);
            if (line.isPresent()) {
                return line.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No ECS JSON line with message '" + message + "' in:\n" + output.getOut());
    }
}
