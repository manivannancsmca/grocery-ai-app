package com.grocery.gateway;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7: the notification stream (Server-Sent Events) through the real gateway. The global response timeout is
 * shortened to 1 s here; the stub keeps the stream open for ~3 s. Proves that the stream route has no response
 * timeout, that events are flushed one by one (not buffered until the end), that the regular notification routes
 * still time out, and that the stream needs a token. Phase 11: the same for the assistant's chat stream, plus the
 * insights report's longer timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s", "GATEWAY_MANAGEMENT_PORT=0"})
class NotificationStreamGatewayIntegrationTest {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);
    static HttpServer stub;
    static final AtomicReference<String> lastEventIdSeen = new AtomicReference<>();

    @Value("${local.server.port}")
    int port;

    @BeforeAll
    static void start() throws Exception {
        REDIS.start();
        GatewayRoutingIntegrationTest.signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        String jwks = new JWKSet(GatewayRoutingIntegrationTest.signingKey.toPublicJWK()).toString();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        stub.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            try {
                if (path.equals("/.well-known/jwks.json")) {
                    respond(exchange, 200, "application/json", jwks);
                } else if (path.equals("/api/notifications/stream")) {
                    lastEventIdSeen.set(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.getResponseHeaders().add("Cache-Control", "no-cache");
                    exchange.sendResponseHeaders(200, 0);                       // chunked, open-ended
                    try (OutputStream out = exchange.getResponseBody()) {
                        write(out, "retry: 3000\n\n");
                        write(out, "event: signal\ndata: {\"type\":\"STOCK_CHANGED\",\"data\":{}}\n\n");
                        Thread.sleep(1_500);                                    // longer than the 1 s timeout
                        write(out, ": hb\n\n");
                        Thread.sleep(1_500);
                        write(out, "event: notification\nid: n-2\ndata: {\"id\":\"n-2\"}\n\n");
                    }
                } else if (path.equals("/api/ai/agents/shopping/chat")) {
                    // Phase 11: the assistant's answer, streamed while the (slow) model writes it
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream out = exchange.getResponseBody()) {
                        write(out, "event: conversation\ndata: {\"conversationId\":\"c-1\",\"agent\":\"shopping\"}\n\n");
                        Thread.sleep(1_500);                                    // longer than the 1 s timeout
                        write(out, "event: token\ndata: {\"text\":\"Added 2 × Basmati Rice\"}\n\n");
                        Thread.sleep(1_500);
                        write(out, "event: done\ndata: {\"messageId\":\"m-1\",\"finishReason\":\"STOP\"}\n\n");
                    }
                } else if (path.equals("/api/ai/agents/admin-bi/insights")
                        || path.equals("/api/notifications/slow") || path.equals("/api/ai/conversations")) {
                    Thread.sleep(2_500);
                    respond(exchange, 200, "application/json", "{\"headline\":\"ok\"}");
                } else {
                    respond(exchange, 200, "application/json", "{\"count\":0}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // the gateway gave up on a slow request: expected in the timeout test
            }
        });
        stub.start();
    }

    private static void write(OutputStream out, String text) throws java.io.IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String type, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
        REDIS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        for (String service : List.of("IDENTITY", "CUSTOMER", "VENDOR", "RESTAURANT", "DELIVERY", "CATALOG_COMMAND",
                "CATALOG_QUERY", "INVENTORY", "PRICING", "CART", "ORDER", "PAYMENT", "NOTIFICATION", "AI_ORCHESTRATOR")) {
            registry.add(service + "_SERVICE_URL", () -> base);
        }
        registry.add("IDENTITY_JWKS_URI", () -> base + "/.well-known/jwks.json");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
    }

    @Test
    void theStreamOutlivesTheResponseTimeoutAndEventsArriveAsTheyAreWritten() throws Exception {
        String token = GatewayRoutingIntegrationTest.validToken(UUID.randomUUID(), "CUSTOMER");
        long start = System.nanoTime();
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(request("/api/notifications/stream")
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "text/event-stream")
                        .header("Last-Event-ID", "n-1").GET().build(),
                HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        List<String> lines = new ArrayList<>();
        long firstEventAtMs = -1;
        try (Stream<String> body = response.body()) {
            for (String line : (Iterable<String>) body::iterator) {
                lines.add(line);
                if (firstEventAtMs < 0 && line.startsWith("event: signal")) {
                    firstEventAtMs = (System.nanoTime() - start) / 1_000_000;
                }
            }
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(lines).contains("retry: 3000", "event: signal", ": hb", "event: notification", "id: n-2");
        assertThat(firstEventAtMs).as("first event flushed before the stream ends").isBetween(0L, 1_200L);
        assertThat(totalMs).as("the stream stayed open past the 1 s response timeout").isGreaterThanOrEqualTo(2_900L);
        assertThat(lastEventIdSeen.get()).as("Last-Event-ID reaches the service").isEqualTo("n-1");
    }

    @Test
    void theOtherNotificationRoutesKeepTheResponseTimeout() throws Exception {
        String token = GatewayRoutingIntegrationTest.validToken(UUID.randomUUID(), "CUSTOMER");
        HttpResponse<String> slow = HttpClient.newHttpClient().send(request("/api/notifications/slow")
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(slow.statusCode()).isEqualTo(504);
        assertThat(slow.body()).contains("GATEWAY_TIMEOUT");

        HttpResponse<String> count = HttpClient.newHttpClient().send(request("/api/notifications/unread-count")
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(count.statusCode()).isEqualTo(200);
        assertThat(count.body()).contains("\"count\":0");
    }

    /** Phase 11: the assistant's chat stream has no response timeout either, and is flushed event by event. */
    @Test
    void theAssistantChatStreamOutlivesTheResponseTimeout() throws Exception {
        String token = GatewayRoutingIntegrationTest.validToken(UUID.randomUUID(), "CUSTOMER");
        long start = System.nanoTime();
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(request("/api/ai/agents/shopping/chat")
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"add 2 basmati\"}")).build(),
                HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> lines = new ArrayList<>();
        long firstEventAtMs = -1;
        try (Stream<String> body = response.body()) {
            for (String line : (Iterable<String>) body::iterator) {
                lines.add(line);
                if (firstEventAtMs < 0 && line.startsWith("event: conversation")) {
                    firstEventAtMs = (System.nanoTime() - start) / 1_000_000;
                }
            }
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(lines).contains("event: conversation", "event: token", "event: done");
        assertThat(firstEventAtMs).as("first event flushed before the answer ends").isBetween(0L, 1_200L);
        assertThat(totalMs).as("the answer outlived the 1 s response timeout").isGreaterThanOrEqualTo(2_900L);
    }

    /** Phase 11: the insights report may wait for the model longer; every other assistant route keeps the timeout. */
    @Test
    void theInsightsReportMayTakeLongerThanTheOtherAssistantRoutes() throws Exception {
        String admin = GatewayRoutingIntegrationTest.validToken(UUID.randomUUID(), "OPS_ADMIN");
        HttpResponse<String> insights = HttpClient.newHttpClient().send(request("/api/ai/agents/admin-bi/insights")
                .header("Authorization", "Bearer " + admin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(insights.statusCode()).isEqualTo(200);
        assertThat(insights.body()).contains("\"headline\":\"ok\"");

        HttpResponse<String> slow = HttpClient.newHttpClient().send(request("/api/ai/conversations")
                .header("Authorization", "Bearer " + admin).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(slow.statusCode()).isEqualTo(504);
        assertThat(slow.body()).contains("GATEWAY_TIMEOUT");
    }

    @Test
    void theStreamAndTheInboxNeedAToken() throws Exception {
        for (String path : List.of("/api/notifications/stream", "/api/notifications", "/api/notifications/unread-count")) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request(path).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(401);
        }
        HttpResponse<String> health = HttpClient.newHttpClient().send(request("/api/health/notification").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
    }
}
