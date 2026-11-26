package com.grocery.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.ClassUtils;
import org.springframework.web.server.ServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real gateway (security, filters, routes, Redis rate limiter) against a stub backend
 * that also serves a JWKS. Tokens are signed with a test RSA key, exactly like identity-service does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "RATE_LIMIT_AUTH_REPLENISH=1", "RATE_LIMIT_AUTH_BURST=3",
        "GATEWAY_MANAGEMENT_PORT=0"})   // the internal management port (9080 in Docker/AWS), random here
@Import(GatewayRoutingIntegrationTest.ChainRunCounter.class)
@AutoConfigureMetrics   // Phase 13: the real Prometheus registry and /actuator/prometheus (tests disable export otherwise)
class GatewayRoutingIntegrationTest {

    static final String ISSUER = "https://identity.grocery.local";
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);
    /** One stub server per backend (named like its *_SERVICE_URL variable), so a test can tell which service answered. */
    static final List<String> STUBBED_SERVICES = List.of("IDENTITY", "CUSTOMER", "VENDOR", "RESTAURANT", "CATALOG_COMMAND",
            "CATALOG_QUERY", "INVENTORY", "PRICING", "CART", "ORDER", "PAYMENT", "NOTIFICATION", "AI_ORCHESTRATOR",
            "REVIEW", "REPORTING", "KAFKA_CONNECT");
    static RSAKey signingKey;
    static final Map<String, HttpServer> stubs = new ConcurrentHashMap<>();
    static final AtomicReference<String> lastService = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();
    static final AtomicReference<String> lastQuery = new AtomicReference<>();
    static final Map<String, String> lastHeaders = new ConcurrentHashMap<>();

    @Value("${local.server.port}")
    int port;
    @Value("${local.management.port}")
    int managementPort;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    List<org.springframework.cloud.gateway.config.HttpClientCustomizer> httpClientCustomizers;
    WebTestClient client;
    /** The gateway's actuator: health, info and its own metrics, on the internal management port only. */
    WebTestClient management;

    @BeforeAll
    static void start() throws Exception {
        REDIS.start();
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        for (String service : STUBBED_SERVICES) {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                byte[] body;
                if (path.equals("/.well-known/jwks.json")) {
                    body = jwks.getBytes(StandardCharsets.UTF_8);
                } else {
                    lastService.set(service);
                    lastPath.set(path);
                    lastQuery.set(exchange.getRequestURI().getRawQuery());
                    lastHeaders.clear();
                    exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), v.getFirst()));
                    body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
                    String correlation = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
                    if (correlation != null) {
                        exchange.getResponseHeaders().add("X-Correlation-Id", correlation);  // services echo it
                    }
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            stub.start();
            stubs.put(service, stub);
        }
    }

    @AfterAll
    static void stop() {
        stubs.values().forEach(stub -> stub.stop(0));
        REDIS.stop();
    }

    static String baseUrl(String service) {
        return "http://127.0.0.1:" + stubs.get(service).getAddress().getPort();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        for (String service : STUBBED_SERVICES) {
            String base = baseUrl(service);
            registry.add(service.equals("KAFKA_CONNECT") ? "KAFKA_CONNECT_URL" : service + "_SERVICE_URL", () -> base);
        }
        // delivery-service is "down": nothing listens on this port
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registry.add("DELIVERY_SERVICE_URL", () -> "http://127.0.0.1:" + closedPort);
        String jwksUri = baseUrl("IDENTITY") + "/.well-known/jwks.json";
        registry.add("IDENTITY_JWKS_URI", () -> jwksUri);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        management = WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
    }

    static String token(UUID userId, List<String> roles, Instant issuedAt, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience("grocery-api").subject(userId.toString())
                .issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt))
                .claim("iat_ms", issuedAt.toEpochMilli()).claim("roles", roles).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    static String validToken(UUID userId, String... roles) throws Exception {
        return token(userId, List.of(roles), Instant.now(), Instant.now().plusSeconds(600));
    }

    @Test
    void publicAuthRoutesWorkWithoutAToken() {
        client.post().uri("/api/auth/register").exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/auth/register");
    }

    @Test
    void protectedRoutesRequireAValidToken() throws Exception {
        client.get().uri("/api/vendors/me").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED");

        client.get().uri("/api/vendors/me").header("Authorization", "Bearer garbage").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo("INVALID_TOKEN");

        String expired = token(UUID.randomUUID(), List.of("VENDOR"),
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        client.get().uri("/api/vendors/me").header("Authorization", "Bearer " + expired).exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo("TOKEN_EXPIRED");

        client.get().uri("/api/vendors/me").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "VENDOR"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/vendors/me");
        assertThat(lastHeaders.get("authorization")).as("token is relayed to the service").startsWith("Bearer ");
    }

    /** The MCP pilot: each MCP server behind its service's route, a token required (POST is never a public read). */
    @Test
    void theMcpServersAreRoutedToTheirServicesForSignedInCallers() throws Exception {
        client.post().uri("/api/mcp/catalog").bodyValue("{}").exchange().expectStatus().isUnauthorized();
        String token = "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER");
        client.post().uri("/api/mcp/catalog").header("Authorization", token).bodyValue("{}").exchange()
                .expectStatus().isOk();
        assertThat(lastService.get()).isEqualTo("CATALOG_QUERY");
        assertThat(lastPath.get()).isEqualTo("/api/mcp/catalog");
        assertThat(lastHeaders.get("authorization")).isEqualTo(token);
        client.post().uri("/api/mcp/orders").header("Authorization", token).bodyValue("{}").exchange()
                .expectStatus().isOk();
        assertThat(lastService.get()).isEqualTo("ORDER");
        assertThat(lastPath.get()).isEqualTo("/api/mcp/orders");
    }

    @Test
    void adminPathsNeedAnAdminRoleAtTheEdge() throws Exception {
        client.get().uri("/api/admin/vendors").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER"))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo("FORBIDDEN");
        client.get().uri("/api/admin/vendors").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "SUPPORT"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void tokensIssuedBeforeARevocationAreRejected() throws Exception {
        UUID userId = UUID.randomUUID();
        String oldToken = token(userId, List.of("VENDOR"), Instant.now().minusSeconds(60), Instant.now().plusSeconds(600));
        redis.opsForValue().set("auth:revoked-before:" + userId, Long.toString(Instant.now().toEpochMilli()));

        client.get().uri("/api/vendors/me").header("Authorization", "Bearer " + oldToken).exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo("TOKEN_REVOKED");

        String newToken = token(userId, List.of("VENDOR"), Instant.now().plusMillis(5), Instant.now().plusSeconds(600));
        client.get().uri("/api/vendors/me").header("Authorization", "Bearer " + newToken).exchange()
                .expectStatus().isOk();
    }

    @Test
    void spoofedIdentityHeadersAreStripped() throws Exception {
        client.get().uri("/api/customers/me")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER"))
                .header("X-User-Id", "someone-else").header("X-User-Roles", "SUPER_ADMIN")
                .header("X-Internal-Token", "forged")
                .exchange().expectStatus().isOk();
        assertThat(lastHeaders).doesNotContainKeys("x-user-id", "x-user-roles", "x-internal-token");
    }

    /**
     * Phase 11: ai-orchestrator-service executes an approved action through the gateway with the approver's token, a
     * signed X-Approval-Token and Idempotency-Key = approvalId. Both reach the target untouched; the target verifies
     * the approval token itself (signature, audience, method/path, body hash, subject, single use).
     */
    @Test
    void approvalTokensAndIdempotencyKeysPassThroughUntouched() throws Exception {
        String approvalToken = "eyJ0eXAiOiJhcHByb3ZhbCtqd3QiLCJhbGciOiJSUzI1NiIsImtpZCI6ImsxIn0.e30.c2ln";
        String approvalId = "0197a3c1-7f2e-7c1d-9a4b-1f2e3d4c5b6a";
        client.post().uri("/api/orders").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER"))
                .header("X-Approval-Token", approvalToken).header("Idempotency-Key", approvalId)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/orders");
        assertThat(lastHeaders.get("x-approval-token")).isEqualTo(approvalToken);
        assertThat(lastHeaders.get("idempotency-key")).isEqualTo(approvalId);

        client.post().uri("/api/admin/refunds")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "FINANCE_ADMIN"))
                .header("X-Approval-Token", approvalToken).header("Idempotency-Key", approvalId)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/admin/refunds");
        assertThat(lastHeaders.get("x-approval-token")).isEqualTo(approvalToken);
        assertThat(lastHeaders.get("idempotency-key")).isEqualTo(approvalId);
    }

    @Test
    void assistantsAndApprovalsAreRoutedToTheOrchestratorForEverySignedInUser() throws Exception {
        for (String role : List.of("CUSTOMER", "VENDOR", "OPS_ADMIN")) {
            String token = validToken(UUID.randomUUID(), role);
            for (String path : List.of("/api/ai/agents", "/api/ai/status", "/api/ai/conversations/c1",
                    "/api/approvals", "/api/approvals/a1")) {
                client.get().uri(path).header("Authorization", "Bearer " + token).exchange().expectStatus().isOk();
                assertThat(lastPath.get()).as(role + " " + path).isEqualTo(path);
            }
        }
        String customer = validToken(UUID.randomUUID(), "CUSTOMER");
        client.post().uri("/api/ai/agents/shopping/chat").header("Authorization", "Bearer " + customer)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/ai/agents/shopping/chat");
        client.get().uri("/api/approvals?scope=to-decide&status=PENDING_APPROVAL")
                .header("Authorization", "Bearer " + customer).exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/approvals");
        assertThat(lastQuery.get()).isEqualTo("scope=to-decide&status=PENDING_APPROVAL");
        for (String decision : List.of("approve", "reject", "cancel")) {
            client.post().uri("/api/approvals/a1/" + decision).header("Authorization", "Bearer " + customer)
                    .exchange().expectStatus().isOk();
            assertThat(lastPath.get()).isEqualTo("/api/approvals/a1/" + decision);
        }
        client.delete().uri("/api/ai/conversations/c1").header("Authorization", "Bearer " + customer)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/ai/conversations/c1");

        // no token, no assistant
        for (String path : List.of("/api/ai/agents", "/api/approvals", "/api/approvals/a1")) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
        client.post().uri("/api/ai/agents/shopping/chat").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theAiAuditAndMonitoringAreAdminOnlyAndTheHealthIsPublic() throws Exception {
        for (String path : List.of("/api/admin/ai/tool-invocations", "/api/admin/ai/summary")) {
            for (String admin : List.of("SUPPORT", "FINANCE_ADMIN", "DELIVERY_MANAGER")) {
                client.get().uri(path).header("Authorization", "Bearer " + validToken(UUID.randomUUID(), admin))
                        .exchange().expectStatus().isOk();
                assertThat(lastPath.get()).isEqualTo(path);
            }
            client.get().uri(path).header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER"))
                    .exchange().expectStatus().isForbidden()
                    .expectBody().jsonPath("$.errorCode").isEqualTo("FORBIDDEN");
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
        client.post().uri("/api/ai/agents/admin-bi/insights")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "OPS_ADMIN"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/ai/agents/admin-bi/insights");

        client.get().uri("/api/health/ai").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        assertThat(lastPath.get()).isEqualTo("/actuator/health");
    }

    @Test
    void theApprovalKeysAndTheOrchestratorsInternalEndpointsAreNeverRouted() throws Exception {
        String token = validToken(UUID.randomUUID(), "SUPER_ADMIN");
        lastPath.set(null);
        client.get().uri("/.well-known/approval-jwks.json").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isNotFound();
        client.get().uri("/.well-known/approval-jwks.json").exchange().expectStatus().isUnauthorized();
        for (String path : List.of("/internal/approvals/a1", "/internal/ai/tools")) {
            client.post().uri(path).header("Authorization", "Bearer " + token).exchange().expectStatus().isNotFound();
        }
        client.get().uri("/api/ai-orchestrator/actuator/env").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isNotFound();
        assertThat(lastPath.get()).as("nothing reached a service").isNull();
    }

    @Test
    void correlationIdIsPropagatedAndReturnedExactlyOnce() throws Exception {
        client.get().uri("/api/vendors/me").header("X-Correlation-Id", "web-corr-0001")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "VENDOR"))
                .exchange().expectStatus().isOk()
                .expectHeader().values("X-Correlation-Id",
                        values -> assertThat(values).containsExactly("web-corr-0001"));
        assertThat(lastHeaders.get("x-correlation-id")).isEqualTo("web-corr-0001");
    }

    @Test
    void healthRoutesArePublicAndRewritten() {
        client.get().uri("/api/health/vendor").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        assertThat(lastPath.get()).isEqualTo("/actuator/health");
        client.get().uri("/api/health/gateway").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void corsAllowsOnlyTheFrontendOriginAndCoversErrorResponses() {
        client.options().uri("/api/vendors/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:6420")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:6420")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");

        client.options().uri("/api/vendors/me")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange().expectStatus().isForbidden();

        client.get().uri("/api/vendors/me").header(HttpHeaders.ORIGIN, "http://localhost:6420").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:6420");
    }

    @Test
    void loginIsRateLimitedPerClientIp() {
        int tooMany = 0;
        for (int i = 0; i < 8; i++) {
            int status = client.post().uri("/api/auth/login").exchange().returnResult(String.class).getStatus().value();
            if (status == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).as("burst of 3, refill 1/s").isGreaterThan(0);
    }

    @Test
    void passwordForgotAndResetArePublicChangeNeedsATokenAndAllShareThePerIpAuthLimit() throws Exception {
        // A client IP of its own (one trusted proxy: the last X-Forwarded-For entry), so other tests' calls don't count
        String ip = "10.77.0.1";
        for (String path : List.of("/api/auth/password/forgot", "/api/auth/password/reset")) {
            lastService.set(null);
            client.post().uri(path).header("X-Forwarded-For", ip).exchange().expectStatus().isOk();
            assertThat(lastService.get()).as(path).isEqualTo("IDENTITY");
            assertThat(lastPath.get()).isEqualTo(path);
        }
        lastService.set(null);
        client.post().uri("/api/auth/password/change").header("X-Forwarded-For", ip).exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED");
        assertThat(lastService.get()).as("refused at the edge").isNull();
        client.post().uri("/api/auth/password/change").header("X-Forwarded-For", ip)
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "VENDOR"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/auth/password/change");

        // The burst of 3 is used up: more forgot / reset calls from this IP are refused for a while, like logins
        int tooMany = 0;
        for (int i = 0; i < 4; i++) {
            int status = client.post().uri("/api/auth/password/forgot").header("X-Forwarded-For", ip).exchange()
                    .returnResult(String.class).getStatus().value();
            if (status == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).as("burst of 3, refill 1/s").isGreaterThan(0);
        client.post().uri("/api/auth/password/reset").header("X-Forwarded-For", "10.77.0.2").exchange()
                .expectStatus().isOk();
    }

    @Test
    void unreachableServiceIsReportedAs503ProblemDetail() throws Exception {
        client.get().uri("/api/health/delivery").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.errorCode").isEqualTo("SERVICE_UNAVAILABLE");
        client.get().uri("/api/delivery/partners/me")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "DELIVERY_PARTNER"))
                .exchange().expectStatus().isEqualTo(503);
    }

    /** Counts how often the filter chain behind the security/revocation filters runs, per path. */
    @TestConfiguration
    static class ChainRunCounter {
        static final Map<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();

        @Bean
        GlobalFilter chainRunCounter() {
            return new OrderedChainRunCounter();
        }

        static class OrderedChainRunCounter implements GlobalFilter, Ordered {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                RUNS.computeIfAbsent(exchange.getRequest().getPath().value(), p -> new AtomicInteger()).incrementAndGet();
                return chain.filter(exchange);
            }

            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE + 10;   // after the token-revocation filter
            }
        }
    }

    @Test
    void eachAuthenticatedRequestRunsTheFilterChainExactlyOnce() throws Exception {
        String token = validToken(UUID.randomUUID(), "VENDOR");
        for (int i = 0; i < 3; i++) {
            client.post().uri("/api/vendor/products/once-check").header("Authorization", "Bearer " + token)
                    .exchange().expectStatus().isOk();
        }
        assertThat(ChainRunCounter.RUNS.get("/api/vendor/products/once-check")).hasValue(3);
    }

    @Test
    void catalogBrowsingIsPublicButOnlyForReads() throws Exception {
        client.get().uri("/api/search/products?q=tomato").exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/search/products");
        client.get().uri("/api/catalog/restaurants/abc").exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/catalog/restaurants/abc");

        client.post().uri("/api/catalog/products").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/vendor/products").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/restaurant/menu").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theStoreDirectoryIsAGuestReadServedByVendorService() throws Exception {
        String storeId = "0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e701";
        assertRouted(HttpMethod.GET, "/api/stores/nearby?lat=12.9202&lng=80.1445", null, "VENDOR", "/api/stores/nearby");
        assertThat(lastQuery.get()).isEqualTo("lat=12.9202&lng=80.1445");
        assertRouted(HttpMethod.GET, "/api/stores/" + storeId, null, "VENDOR", "/api/stores/" + storeId);
        assertRouted(HttpMethod.GET, "/api/stores/" + storeId + "?lat=12.9202&lng=80.1445", null, "VENDOR",
                "/api/stores/" + storeId);
        // a signed-in customer reads it too (the token is passed on)
        String customer = validToken(UUID.randomUUID(), "CUSTOMER");
        assertRouted(HttpMethod.GET, "/api/stores/nearby?lat=12.9202&lng=80.1445", customer, "VENDOR",
                "/api/stores/nearby");
        assertThat(lastHeaders.get("authorization")).isEqualTo("Bearer " + customer);
        // reads only
        assertRejected(HttpMethod.POST, "/api/stores/nearby", null, 401);
        assertRejected(HttpMethod.PUT, "/api/stores/" + storeId, null, 401);
        assertRejected(HttpMethod.DELETE, "/api/stores/" + storeId, null, 401);
        // the neighbouring store workspace paths still need a token
        assertRejected(HttpMethod.GET, "/api/store/reviews", null, 401);
        assertRejected(HttpMethod.GET, "/api/store/returns", null, 401);
        assertRejected(HttpMethod.GET, "/api/vendors/me", null, 401);
    }

    @Test
    void catalogWriteAndWorkspaceRoutesReachTheirServices() throws Exception {
        client.post().uri("/api/vendor/products")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "VENDOR"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/vendor/products");

        client.get().uri("/api/restaurant/menu")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "RESTAURANT_OWNER"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/restaurant/menu");

        client.post().uri("/api/admin/categories")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "VENDOR"))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/admin/catalog/republish")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "OPS_ADMIN"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/admin/catalog/republish");

        client.get().uri("/api/health/catalog-query").exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/actuator/health");
    }

    @Test
    void inventoryAndPricingRoutesReachTheirServicesButTheQuoteStaysInternal() throws Exception {
        String vendor = validToken(UUID.randomUUID(), "VENDOR");
        client.post().uri("/api/vendor/inventory/p1/adjustments").header("Authorization", "Bearer " + vendor)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/vendor/inventory/p1/adjustments");
        client.put().uri("/api/vendor/prices/p1").header("Authorization", "Bearer " + vendor)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/vendor/prices/p1");

        client.get().uri("/api/vendor/inventory").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/admin/price-change-requests/r1/approve").header("Authorization", "Bearer " + vendor)
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/admin/price-change-requests/r1/approve")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "FINANCE_ADMIN"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/admin/price-change-requests/r1/approve");

        // Internal endpoint: cart/order call pricing-service directly; the gateway has no route for it.
        client.post().uri("/api/prices/quote").header("Authorization", "Bearer " + vendor)
                .exchange().expectStatus().isNotFound();
        client.get().uri("/api/health/pricing").exchange().expectStatus().isOk();
    }

    @Test
    void cartIsRoutedAndInternalEndpointsAreNeverExposed() throws Exception {
        String customer = validToken(UUID.randomUUID(), "CUSTOMER");
        client.get().uri("/api/cart").header("Authorization", "Bearer " + customer).exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/cart");
        client.get().uri("/api/cart/preview?addressId=a1").header("Authorization", "Bearer " + customer)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/cart/preview");
        client.get().uri("/api/cart").exchange().expectStatus().isUnauthorized();

        for (String path : List.of("/internal/prices/quote", "/internal/inventory/availability",
                "/internal/catalog/products/lookup", "/internal/restaurants/r1/menu-quote")) {
            client.post().uri(path).header("Authorization", "Bearer " + customer)
                    .exchange().expectStatus().isNotFound();
        }
        client.get().uri("/internal/vendors/v1/store").header("Authorization", "Bearer " + customer)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void ordersAndPaymentsAreRoutedAndOnlyTheWebhookIsPublic() throws Exception {
        String customer = validToken(UUID.randomUUID(), "CUSTOMER");
        client.post().uri("/api/orders").header("Authorization", "Bearer " + customer)
                .header("Idempotency-Key", "0197a3c1-7f2e-7c1d-9a4b-1f2e3d4c5b6a")
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/orders");
        assertThat(lastHeaders.get("idempotency-key")).isEqualTo("0197a3c1-7f2e-7c1d-9a4b-1f2e3d4c5b6a");
        client.post().uri("/api/orders/o1/cancel").header("Authorization", "Bearer " + customer)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/orders/o1/cancel");
        client.get().uri("/api/payments/orders/o1/checkout").header("Authorization", "Bearer " + customer)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/payments/orders/o1/checkout");
        client.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/payments/razorpay/verify").exchange().expectStatus().isUnauthorized();

        // Razorpay posts webhooks without a token; the signature is checked by payment-service
        client.post().uri("/api/payments/razorpay/webhook").header("X-Razorpay-Signature", "abc")
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/payments/razorpay/webhook");
        assertThat(lastHeaders.get("x-razorpay-signature")).isEqualTo("abc");
        client.get().uri("/api/payments/razorpay/webhook").exchange().expectStatus().isUnauthorized();

        for (String path : List.of("/api/admin/orders", "/api/admin/stats", "/api/admin/payments")) {
            client.get().uri(path).header("Authorization", "Bearer " + customer).exchange().expectStatus().isForbidden();
            client.get().uri(path).header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "FINANCE_ADMIN"))
                    .exchange().expectStatus().isOk();
            assertThat(lastPath.get()).isEqualTo(path);
        }
        client.post().uri("/api/admin/refunds").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "FINANCE_ADMIN"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/admin/refunds");
        client.get().uri("/api/health/order").exchange().expectStatus().isOk();
        client.get().uri("/api/health/payment").exchange().expectStatus().isOk();
    }

    @Test
    void notificationsAreRoutedForEveryRole() throws Exception {
        for (String role : List.of("CUSTOMER", "VENDOR", "RESTAURANT_OWNER", "DELIVERY_PARTNER", "OPS_ADMIN")) {
            String token = validToken(UUID.randomUUID(), role);
            for (String path : List.of("/api/notifications", "/api/notifications/unread-count")) {
                client.get().uri(path).header("Authorization", "Bearer " + token).exchange().expectStatus().isOk();
                assertThat(lastPath.get()).isEqualTo(path);
            }
            client.post().uri("/api/notifications/read-all").header("Authorization", "Bearer " + token)
                    .exchange().expectStatus().isOk();
            assertThat(lastPath.get()).isEqualTo("/api/notifications/read-all");
        }
        client.get().uri("/api/notifications").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/health/notification").exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/actuator/health");
    }

    @Test
    void storeOrderBoardsAndReportsAreRouted() throws Exception {
        String vendor = validToken(UUID.randomUUID(), "VENDOR");
        for (String path : List.of("/api/vendor/orders", "/api/vendor/orders/o1", "/api/vendor/reports/sales")) {
            client.get().uri(path).header("Authorization", "Bearer " + vendor).exchange().expectStatus().isOk();
            assertThat(lastPath.get()).isEqualTo(path);
        }
        client.post().uri("/api/vendor/orders/o1/accept").header("Authorization", "Bearer " + vendor)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/vendor/orders/o1/accept");
        String owner = validToken(UUID.randomUUID(), "RESTAURANT_OWNER");
        for (String path : List.of("/api/restaurant/orders", "/api/restaurant/reports/sales")) {
            client.get().uri(path).header("Authorization", "Bearer " + owner).exchange().expectStatus().isOk();
            assertThat(lastPath.get()).isEqualTo(path);
        }
        client.get().uri("/api/vendor/orders").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void deliveryPartnerTrackingAndAdminDeliveryRoutesAreRouted() throws Exception {
        // delivery-service is "down" in this test (closed port): routing is proven by the 503 ProblemDetail
        String partner = validToken(UUID.randomUUID(), "DELIVERY_PARTNER");
        for (String path : List.of("/api/delivery/offers", "/api/delivery/deliveries/active",
                "/api/delivery/orders/o1/tracking")) {
            client.get().uri(path).header("Authorization", "Bearer " + partner).exchange()
                    .expectStatus().isEqualTo(503).expectBody().jsonPath("$.errorCode").isEqualTo("SERVICE_UNAVAILABLE");
        }
        client.get().uri("/api/admin/deliveries").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "DELIVERY_MANAGER"))
                .exchange().expectStatus().isEqualTo(503);
        client.get().uri("/api/admin/deliveries").header("Authorization", "Bearer " + partner)
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void eachServicesDeadLettersAreReachableUnderOneAdminPrefix() throws Exception {
        String ops = validToken(UUID.randomUUID(), "OPS_ADMIN");
        for (String service : List.of("identity", "customer", "vendor", "restaurant", "catalog-command", "catalog-query",
                "inventory", "pricing", "cart", "order", "payment", "notification", "review", "reporting")) {
            client.get().uri("/api/admin/ops/" + service + "/dead-letters?status=PENDING").header("Authorization", "Bearer " + ops)
                    .exchange().expectStatus().isOk();
            assertThat(lastService.get()).as(service).isEqualTo(service.toUpperCase().replace('-', '_'));
            assertThat(lastPath.get()).as(service).isEqualTo("/api/admin/dead-letters");
            assertThat(lastQuery.get()).isEqualTo("status=PENDING");
        }
        client.post().uri("/api/admin/ops/order/dead-letters/order.events.DLT:0:7/replay").header("Authorization", "Bearer " + ops)
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/api/admin/dead-letters/order.events.DLT:0:7/replay");
        client.get().uri("/api/admin/ops/order/dead-letters").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void connectorHealthIsAdminOnlyAndRestartIsForOperatorsOnly() throws Exception {
        client.get().uri("/api/admin/system/connectors").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "SUPPORT"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/connectors");
        assertThat(lastQuery.get()).isEqualTo("expand=status");
        client.post().uri("/api/admin/system/connectors/order-outbox/restart").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "SUPPORT"))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/admin/system/connectors/order-outbox/restart").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "OPS_ADMIN"))
                .exchange().expectStatus().isOk();
        assertThat(lastPath.get()).isEqualTo("/connectors/order-outbox/restart");
        assertThat(lastQuery.get()).contains("includeTasks=true").contains("onlyFailed=true");
        client.get().uri("/api/admin/system/connectors").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/admin/system/connectors").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void downstreamDnsAnswersAreCachedOnlyBriefly() {
        // A replaced container keeps its name but gets a new IP: the gateway must not keep the old one for minutes.
        assertThat(httpClientCustomizers).anySatisfy(customizer ->
                assertThat(customizer.getClass().getName()).contains("DownstreamClientConfig"));
        reactor.netty.http.client.HttpClient customized = reactor.netty.http.client.HttpClient.create();
        for (var customizer : httpClientCustomizers) {
            customized = customizer.customize(customized);
        }
        assertThat(customized.configuration().resolver()).as("custom DNS resolver configured").isNotNull();
    }

    @Test
    void unknownRoutesAreNotFoundForAuthenticatedUsers() throws Exception {
        client.get().uri("/api/does-not-exist").header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER"))
                .exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------------------------------------ Phase 12

    /** Sends one request and asserts which stubbed service received it, and at which path. */
    void assertRouted(HttpMethod method, String uri, String token, String service, String path) {
        lastService.set(null);
        lastPath.set(null);
        WebTestClient.RequestBodySpec request = client.method(method).uri(uri);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.exchange().expectStatus().isOk();
        assertThat(lastService.get()).as(method + " " + uri).isEqualTo(service);
        assertThat(lastPath.get()).as(method + " " + uri).isEqualTo(path);
    }

    /** Asserts the gateway answers {@code status} itself: the request never reaches a service. */
    void assertRejected(HttpMethod method, String uri, String token, int status) {
        lastService.set(null);
        WebTestClient.RequestBodySpec request = client.method(method).uri(uri);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.exchange().expectStatus().isEqualTo(status);
        assertThat(lastService.get()).as(method + " " + uri + " reached no service").isNull();
    }

    @Test
    void reviewsAreRoutedToReviewServiceAndOnlyThePublishedReviewsAndSummaryArePublic() throws Exception {
        // guests read the published reviews and the rating summary of a product / restaurant page
        assertRouted(HttpMethod.GET, "/api/reviews?targetType=PRODUCT&targetId=p1&sort=highest", null, "REVIEW", "/api/reviews");
        assertThat(lastQuery.get()).isEqualTo("targetType=PRODUCT&targetId=p1&sort=highest");
        assertRouted(HttpMethod.GET, "/api/reviews/summary?targetType=RESTAURANT&targetId=r1", null, "REVIEW",
                "/api/reviews/summary");
        // ...but nothing else
        assertRejected(HttpMethod.GET, "/api/reviews/mine", null, 401);
        assertRejected(HttpMethod.GET, "/api/reviews/eligible?orderId=o1", null, 401);
        assertRejected(HttpMethod.POST, "/api/reviews", null, 401);
        assertRejected(HttpMethod.DELETE, "/api/reviews/rv1", null, 401);
        assertRejected(HttpMethod.GET, "/api/store/reviews", null, 401);

        String customer = validToken(UUID.randomUUID(), "CUSTOMER");
        assertRouted(HttpMethod.GET, "/api/reviews/eligible?orderId=o1", customer, "REVIEW", "/api/reviews/eligible");
        assertRouted(HttpMethod.GET, "/api/reviews/mine", customer, "REVIEW", "/api/reviews/mine");
        assertRouted(HttpMethod.POST, "/api/reviews", customer, "REVIEW", "/api/reviews");
        assertRouted(HttpMethod.PUT, "/api/reviews/rv1", customer, "REVIEW", "/api/reviews/rv1");
        assertRouted(HttpMethod.DELETE, "/api/reviews/rv1", customer, "REVIEW", "/api/reviews/rv1");
        assertRouted(HttpMethod.GET, "/api/reviews?targetType=PRODUCT&targetId=p1", customer, "REVIEW", "/api/reviews");

        for (String owner : List.of("VENDOR", "RESTAURANT_OWNER")) {
            String token = validToken(UUID.randomUUID(), owner);
            assertRouted(HttpMethod.GET, "/api/store/reviews?rating=1", token, "REVIEW", "/api/store/reviews");
            assertRouted(HttpMethod.PUT, "/api/store/reviews/rv1/reply", token, "REVIEW", "/api/store/reviews/rv1/reply");
        }

        for (String nonAdmin : List.of("CUSTOMER", "VENDOR", "RESTAURANT_OWNER")) {
            assertRejected(HttpMethod.GET, "/api/admin/reviews", validToken(UUID.randomUUID(), nonAdmin), 403);
        }
        assertRouted(HttpMethod.GET, "/api/admin/reviews?status=FLAGGED", validToken(UUID.randomUUID(), "SUPPORT"),
                "REVIEW", "/api/admin/reviews");
        assertRouted(HttpMethod.POST, "/api/admin/reviews/rv1/publish", validToken(UUID.randomUUID(), "OPS_ADMIN"),
                "REVIEW", "/api/admin/reviews/rv1/publish");
    }

    @Test
    void couponsReachPricingServiceAndAreNotSwallowedByTheStoreWorkspaceRoutes() throws Exception {
        String owner = validToken(UUID.randomUUID(), "RESTAURANT_OWNER");
        assertRouted(HttpMethod.GET, "/api/restaurant/coupons", owner, "PRICING", "/api/restaurant/coupons");
        assertRouted(HttpMethod.POST, "/api/restaurant/coupons", owner, "PRICING", "/api/restaurant/coupons");
        assertRouted(HttpMethod.POST, "/api/restaurant/coupons/c1/pause", owner, "PRICING", "/api/restaurant/coupons/c1/pause");
        // the rest of the owner's workspace still belongs to restaurant-service
        assertRouted(HttpMethod.GET, "/api/restaurant/menu", owner, "RESTAURANT", "/api/restaurant/menu");
        assertRouted(HttpMethod.GET, "/api/restaurant/orders", owner, "RESTAURANT", "/api/restaurant/orders");

        String vendor = validToken(UUID.randomUUID(), "VENDOR");
        assertRouted(HttpMethod.GET, "/api/vendor/coupons", vendor, "PRICING", "/api/vendor/coupons");
        assertRouted(HttpMethod.PUT, "/api/vendor/coupons/c1", vendor, "PRICING", "/api/vendor/coupons/c1");
        assertRouted(HttpMethod.GET, "/api/vendor/coupons/c1/usage", vendor, "PRICING", "/api/vendor/coupons/c1/usage");

        String customer = validToken(UUID.randomUUID(), "CUSTOMER");
        assertRouted(HttpMethod.GET, "/api/coupons?storeId=s1", customer, "PRICING", "/api/coupons");
        assertThat(lastQuery.get()).isEqualTo("storeId=s1");
        assertRejected(HttpMethod.GET, "/api/coupons?storeId=s1", null, 401);
        assertRejected(HttpMethod.GET, "/api/vendor/coupons", null, 401);
        assertRejected(HttpMethod.GET, "/api/restaurant/coupons", null, 401);

        for (String nonAdmin : List.of("CUSTOMER", "VENDOR", "RESTAURANT_OWNER")) {
            assertRejected(HttpMethod.POST, "/api/admin/coupons", validToken(UUID.randomUUID(), nonAdmin), 403);
        }
        String finance = validToken(UUID.randomUUID(), "FINANCE_ADMIN");
        assertRouted(HttpMethod.POST, "/api/admin/coupons", finance, "PRICING", "/api/admin/coupons");
        assertRouted(HttpMethod.GET, "/api/admin/coupons/c1/usage", finance, "PRICING", "/api/admin/coupons/c1/usage");
        assertRouted(HttpMethod.POST, "/api/admin/coupons/c1/resume", finance, "PRICING", "/api/admin/coupons/c1/resume");

        // evaluate / redeem are internal (cart-service / order-service call pricing-service directly)
        assertRejected(HttpMethod.POST, "/internal/coupons/evaluate", customer, 404);
        assertRejected(HttpMethod.POST, "/internal/coupons/redeem", customer, 404);
    }

    @Test
    void returnsReachOrderService() throws Exception {
        String customer = validToken(UUID.randomUUID(), "CUSTOMER");
        assertRouted(HttpMethod.POST, "/api/orders/o1/returns", customer, "ORDER", "/api/orders/o1/returns");
        assertRouted(HttpMethod.GET, "/api/orders/o1/returns", customer, "ORDER", "/api/orders/o1/returns");
        assertRouted(HttpMethod.GET, "/api/returns?page=0&size=20", customer, "ORDER", "/api/returns");
        assertRouted(HttpMethod.GET, "/api/returns/rt1", customer, "ORDER", "/api/returns/rt1");
        assertRejected(HttpMethod.GET, "/api/returns", null, 401);

        for (String owner : List.of("VENDOR", "RESTAURANT_OWNER")) {
            String token = validToken(UUID.randomUUID(), owner);
            assertRouted(HttpMethod.GET, "/api/store/returns?status=REQUESTED", token, "ORDER", "/api/store/returns");
            assertRouted(HttpMethod.POST, "/api/store/returns/rt1/approve", token, "ORDER", "/api/store/returns/rt1/approve");
            assertRouted(HttpMethod.POST, "/api/store/returns/rt1/reject", token, "ORDER", "/api/store/returns/rt1/reject");
            // the neighbouring store path belongs to another service
            assertRouted(HttpMethod.GET, "/api/store/reviews", token, "REVIEW", "/api/store/reviews");
        }
        assertRejected(HttpMethod.GET, "/api/store/returns", null, 401);

        assertRejected(HttpMethod.GET, "/api/admin/returns", customer, 403);
        assertRejected(HttpMethod.POST, "/api/admin/returns/rt1/approve", validToken(UUID.randomUUID(), "VENDOR"), 403);
        assertRouted(HttpMethod.GET, "/api/admin/returns?status=ESCALATED", validToken(UUID.randomUUID(), "SUPPORT"),
                "ORDER", "/api/admin/returns");
        assertRouted(HttpMethod.POST, "/api/admin/returns/rt1/reject", validToken(UUID.randomUUID(), "OPS_ADMIN"),
                "ORDER", "/api/admin/returns/rt1/reject");
    }

    @Test
    void complaintsReachOrderService() throws Exception {
        String customer = validToken(UUID.randomUUID(), "CUSTOMER");
        assertRouted(HttpMethod.POST, "/api/complaints", customer, "ORDER", "/api/complaints");
        assertRouted(HttpMethod.GET, "/api/complaints?status=OPEN&page=0&size=20", customer, "ORDER", "/api/complaints");
        assertThat(lastQuery.get()).isEqualTo("status=OPEN&page=0&size=20");
        assertRouted(HttpMethod.GET, "/api/complaints/c1", customer, "ORDER", "/api/complaints/c1");
        assertRejected(HttpMethod.GET, "/api/complaints", null, 401);
        assertRejected(HttpMethod.POST, "/api/complaints", null, 401);

        // the admin queue: /api/admin/** needs an admin role at the gateway already
        assertRejected(HttpMethod.GET, "/api/admin/complaints", customer, 403);
        assertRejected(HttpMethod.POST, "/api/admin/complaints/c1/resolve", customer, 403);
        assertRouted(HttpMethod.GET, "/api/admin/complaints?status=OPEN&category=DELIVERY&q=CMP-7K3F9Q2M",
                validToken(UUID.randomUUID(), "FINANCE_ADMIN"), "ORDER", "/api/admin/complaints");
        assertRouted(HttpMethod.POST, "/api/admin/complaints/c1/start", validToken(UUID.randomUUID(), "SUPPORT"),
                "ORDER", "/api/admin/complaints/c1/start");
        assertRouted(HttpMethod.POST, "/api/admin/complaints/c1/resolve", validToken(UUID.randomUUID(), "OPS_ADMIN"),
                "ORDER", "/api/admin/complaints/c1/resolve");
    }

    @Test
    void purchasingReachesVendorServiceWhileTheOtherVendorWorkspacePathsKeepTheirServices() throws Exception {
        String vendor = validToken(UUID.randomUUID(), "VENDOR");
        assertRouted(HttpMethod.GET, "/api/vendor/suppliers", vendor, "VENDOR", "/api/vendor/suppliers");
        assertRouted(HttpMethod.POST, "/api/vendor/suppliers", vendor, "VENDOR", "/api/vendor/suppliers");
        assertRouted(HttpMethod.DELETE, "/api/vendor/suppliers/s1", vendor, "VENDOR", "/api/vendor/suppliers/s1");
        assertRouted(HttpMethod.GET, "/api/vendor/purchase-orders?status=SENT", vendor, "VENDOR", "/api/vendor/purchase-orders");
        assertRouted(HttpMethod.POST, "/api/vendor/purchase-orders", vendor, "VENDOR", "/api/vendor/purchase-orders");
        assertRouted(HttpMethod.POST, "/api/vendor/purchase-orders/po1/receive", vendor, "VENDOR",
                "/api/vendor/purchase-orders/po1/receive");
        assertRouted(HttpMethod.POST, "/api/vendor/purchase-orders/po1/send", vendor, "VENDOR",
                "/api/vendor/purchase-orders/po1/send");
        // unchanged neighbours under /api/vendor/
        assertRouted(HttpMethod.GET, "/api/vendor/products", vendor, "CATALOG_COMMAND", "/api/vendor/products");
        assertRouted(HttpMethod.GET, "/api/vendor/inventory", vendor, "INVENTORY", "/api/vendor/inventory");
        assertRouted(HttpMethod.GET, "/api/vendor/prices", vendor, "PRICING", "/api/vendor/prices");
        // the fees in force, for the assistants' getStoreRules tool (any signed-in user)
        assertRouted(HttpMethod.GET, "/api/pricing/delivery-fees", vendor, "PRICING", "/api/pricing/delivery-fees");
        assertRouted(HttpMethod.GET, "/api/vendor/orders", vendor, "VENDOR", "/api/vendor/orders");
        assertRouted(HttpMethod.GET, "/api/vendor/reports/sales", vendor, "VENDOR", "/api/vendor/reports/sales");
        assertRejected(HttpMethod.GET, "/api/vendor/suppliers", null, 401);
        assertRejected(HttpMethod.GET, "/api/vendor/purchase-orders", null, 401);

        assertRejected(HttpMethod.GET, "/api/admin/purchase-orders", vendor, 403);
        assertRouted(HttpMethod.GET, "/api/admin/purchase-orders?vendorId=v1", validToken(UUID.randomUUID(), "FINANCE_ADMIN"),
                "VENDOR", "/api/admin/purchase-orders");
        assertRouted(HttpMethod.GET, "/api/admin/purchase-orders/po1", validToken(UUID.randomUUID(), "SUPPORT"),
                "VENDOR", "/api/admin/purchase-orders/po1");
    }

    @Test
    void emailPreferencesAndTheAdminEmailLogReachNotificationService() throws Exception {
        for (String role : List.of("CUSTOMER", "VENDOR", "DELIVERY_PARTNER", "OPS_ADMIN")) {
            String token = validToken(UUID.randomUUID(), role);
            assertRouted(HttpMethod.GET, "/api/notifications/preferences", token, "NOTIFICATION", "/api/notifications/preferences");
            assertRouted(HttpMethod.PUT, "/api/notifications/preferences", token, "NOTIFICATION", "/api/notifications/preferences");
        }
        assertRejected(HttpMethod.GET, "/api/notifications/preferences", null, 401);

        assertRejected(HttpMethod.GET, "/api/admin/notifications/emails", validToken(UUID.randomUUID(), "CUSTOMER"), 403);
        assertRejected(HttpMethod.GET, "/api/admin/notifications/emails", null, 401);
        assertRouted(HttpMethod.GET, "/api/admin/notifications/emails?status=FAILED&page=0", validToken(UUID.randomUUID(), "SUPPORT"),
                "NOTIFICATION", "/api/admin/notifications/emails");
        assertThat(lastQuery.get()).isEqualTo("status=FAILED&page=0");
    }

    @Test
    void reportsReachReportingServiceForAdminsOnly() throws Exception {
        List<String> reports = List.of("/api/admin/reports/overview", "/api/admin/reports/sales/daily",
                "/api/admin/reports/stores/top", "/api/admin/reports/products/top", "/api/admin/reports/delivery/performance",
                "/api/admin/reports/promotions", "/api/admin/reports/ratings", "/api/admin/reports/purchasing");
        for (String admin : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            String token = validToken(UUID.randomUUID(), admin);
            for (String report : reports) {
                assertRouted(HttpMethod.GET, report, token, "REPORTING", report);
            }
        }
        assertRouted(HttpMethod.GET, "/api/admin/reports/sales/daily?from=2026-10-01&to=2026-10-31&format=csv",
                validToken(UUID.randomUUID(), "FINANCE_ADMIN"), "REPORTING", "/api/admin/reports/sales/daily");
        assertThat(lastQuery.get()).isEqualTo("from=2026-10-01&to=2026-10-31&format=csv");

        for (String nonAdmin : List.of("CUSTOMER", "VENDOR", "RESTAURANT_OWNER", "DELIVERY_PARTNER")) {
            assertRejected(HttpMethod.GET, "/api/admin/reports/overview", validToken(UUID.randomUUID(), nonAdmin), 403);
        }

        // the central audit trail lives in reporting-service too (which admin roles may read it is its own check)
        assertRouted(HttpMethod.GET, "/api/admin/audit?q=GA-7K3F9Q2M&category=ORDERS",
                validToken(UUID.randomUUID(), "OPS_ADMIN"), "REPORTING", "/api/admin/audit");
        assertThat(lastQuery.get()).isEqualTo("q=GA-7K3F9Q2M&category=ORDERS");
        assertRejected(HttpMethod.GET, "/api/admin/audit", validToken(UUID.randomUUID(), "CUSTOMER"), 403);
        assertRejected(HttpMethod.GET, "/api/admin/reports/overview", null, 401);
        // a store's own sales report is not the platform report
        assertRouted(HttpMethod.GET, "/api/vendor/reports/sales", validToken(UUID.randomUUID(), "VENDOR"), "VENDOR",
                "/api/vendor/reports/sales");
        assertRouted(HttpMethod.GET, "/api/restaurant/reports/sales", validToken(UUID.randomUUID(), "RESTAURANT_OWNER"),
                "RESTAURANT", "/api/restaurant/reports/sales");
    }

    @Test
    void eachPlatformSettingsAreaAndItsHistoryReachTheServiceThatOwnsIt() throws Exception {
        String admin = validToken(UUID.randomUUID(), "SUPPORT");
        Map<String, String> owners = Map.of("grocery-stores", "VENDOR", "restaurants", "RESTAURANT", "returns", "ORDER",
                "reviews", "REVIEW", "delivery-fees", "PRICING");
        owners.forEach((area, service) -> {
            String path = "/api/admin/settings/" + area;
            assertRouted(HttpMethod.GET, path, admin, service, path);
            assertRouted(HttpMethod.PUT, path, admin, service, path);
            assertRouted(HttpMethod.GET, path + "/history?page=1&size=20", admin, service, path + "/history");
            assertThat(lastQuery.get()).isEqualTo("page=1&size=20");
        });
        // delivery-service is "down" in this test (closed port): routing is proven by the 503 ProblemDetail
        for (String path : List.of("/api/admin/settings/delivery", "/api/admin/settings/delivery/history")) {
            client.get().uri(path).header("Authorization", "Bearer " + admin).exchange()
                    .expectStatus().isEqualTo(503).expectBody().jsonPath("$.errorCode").isEqualTo("SERVICE_UNAVAILABLE");
        }
        // the area and its history only, and admins only
        client.get().uri("/api/admin/settings/returns/other").header("Authorization", "Bearer " + admin).exchange()
                .expectStatus().isNotFound();
        assertRejected(HttpMethod.GET, "/api/admin/settings/reviews/history", validToken(UUID.randomUUID(), "CUSTOMER"),
                403);
        assertRejected(HttpMethod.GET, "/api/admin/settings/reviews/history", null, 401);
    }

    @Test
    void theNewServicesHealthIsPublic() {
        assertRouted(HttpMethod.GET, "/api/health/review", null, "REVIEW", "/actuator/health");
        assertRouted(HttpMethod.GET, "/api/health/reporting", null, "REPORTING", "/actuator/health");
        client.get().uri("/api/health/reporting").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    // ------------------------------------------------------------------ Phase 13: observability

    @Autowired
    Environment environment;
    @Autowired
    ApplicationContext applicationContext;
    @Autowired
    RouteDefinitionLocator routeDefinitions;

    static final Pattern LE = Pattern.compile("le=\"([^\"]+)\"");
    static final Set<Double> SLO_BUCKETS = Set.of(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0);

    /** The gateway's own /actuator/prometheus, fetched until {@code ready} holds (timers are recorded on completion). */
    List<String> scrapeUntil(java.util.function.Predicate<List<String>> ready) throws InterruptedException {
        List<String> lines = List.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            String body = management.get().uri("/actuator/prometheus").exchange()     // no token, management port
                    .expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            lines = body == null ? List.of() : body.lines().toList();
            if (ready.test(lines)) {
                return lines;
            }
            Thread.sleep(100);
        }
        return lines;
    }

    static Set<Double> buckets(List<String> lines, String bucketMetric, String mustContain) {
        return lines.stream()
                .filter(line -> line.startsWith(bucketMetric + "{") && line.contains(mustContain))
                .map(LE::matcher).filter(Matcher::find).map(m -> m.group(1))
                .filter(le -> !le.equals("+Inf")).map(Double::valueOf)
                .collect(Collectors.toSet());
    }

    @Test
    void prometheusIsTheGatewaysOwnEndpointOnTheManagementPortWithTheCommonTagsAndLatencyBuckets() throws Exception {
        client.get().uri("/api/health/vendor").exchange().expectStatus().isOk();   // one routed request
        lastPath.set(null);
        // never on the public port: the gateway is internet-facing (security scan, Phase 13)
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();

        List<String> lines = scrapeUntil(l -> l.stream().anyMatch(line ->
                line.startsWith("spring_cloud_gateway_requests_seconds_bucket{") && line.contains("routeId=\"health-vendor\"")));
        assertThat(lastPath.get()).as("served by the gateway itself, never routed to a service").isNull();

        String instance = "instance=\"" + environment.getProperty("HOSTNAME",
                environment.getProperty("COMPUTERNAME", "localhost")) + "\"";
        assertThat(lines).as("common tags on every meter").anySatisfy(line -> assertThat(line)
                .startsWith("jvm_memory_used_bytes{").contains("service=\"api-gateway\"", "env=\"local\"", instance));
        // explicit SLO buckets (not a full percentiles histogram) on the route timer and the server timer
        assertThat(buckets(lines, "spring_cloud_gateway_requests_seconds_bucket", "routeId=\"health-vendor\""))
                .isEqualTo(SLO_BUCKETS);
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("spring_cloud_gateway_requests_seconds_bucket{")
                .contains("routeId=\"health-vendor\"", "service=\"api-gateway\"", "env=\"local\"", instance));
        lines = scrapeUntil(l -> !buckets(l, "http_server_requests_seconds_bucket", "service=\"api-gateway\"").isEmpty());
        assertThat(buckets(lines, "http_server_requests_seconds_bucket", "service=\"api-gateway\"")).isEqualTo(SLO_BUCKETS);
    }

    @Test
    void theOpenTelemetryStarterTracesAndSendsW3cTraceContextToTheServices() {
        assertThat(applicationContext.getBean(Tracer.class)).isInstanceOf(OtelTracer.class);
        assertThat(applicationContext.getBeansOfType(SpanExporter.class).values())
                .anySatisfy(exporter -> assertThat(exporter).isInstanceOf(OtlpHttpSpanExporter.class));
        assertThat(ClassUtils.isPresent("io.micrometer.registry.otlp.OtlpMeterRegistry", null))
                .as("metrics are scraped by Prometheus, never pushed over OTLP").isFalse();

        // a trace started upstream is continued: the service gets the same trace id, with the gateway's span as parent
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        client.get().uri("/api/health/vendor").header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .exchange().expectStatus().isOk();
        assertThat(lastHeaders.get("traceparent")).matches("00-" + traceId + "-[0-9a-f]{16}-01")
                .doesNotContain("00f067aa0ba902b7");

        // without one, the trace starts at the gateway (sampled: TRACING_SAMPLING_PROBABILITY defaults to 1.0; the
        // OpenTelemetry SDK also sets W3C level 2's "random trace id" flag on traces it starts: flags 03)
        client.get().uri("/api/health/vendor").exchange().expectStatus().isOk();
        assertThat(lastHeaders.get("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[13]").doesNotContain(traceId);
    }

    @Test
    void actuatorEndpointsAreNeverRoutedPublicly() throws Exception {
        // configuration: no route matches /actuator/**, and the only rewrite into a service's actuator is its health
        List<RouteDefinition> routes = routeDefinitions.getRouteDefinitions().collectList().block(Duration.ofSeconds(10));
        assertThat(routes).isNotEmpty().allSatisfy(route -> {
            assertThat(route.getPredicates()).allSatisfy(predicate ->
                    assertThat(predicate.getArgs().values()).as(route.getId()).noneMatch(v -> v.contains("/actuator")));
            route.getFilters().forEach(filter -> assertThat(filter.getArgs().values()).as(route.getId())
                    .filteredOn(v -> v.contains("/actuator")).allMatch("/actuator/health"::equals));
        });

        // the public port serves no actuator at all (not even with an admin token); nothing reaches a service
        String admin = validToken(UUID.randomUUID(), "SUPER_ADMIN");
        lastService.set(null);
        for (String path : List.of("/actuator/env", "/actuator/metrics", "/actuator/loggers", "/actuator/heapdump",
                "/actuator/gateway/routes", "/actuator/prometheus", "/actuator/health", "/actuator/info")) {
            client.get().uri(path).header("Authorization", "Bearer " + admin).exchange().expectStatus().isNotFound();
            client.get().uri(path).exchange().expectStatus().value(status ->
                    assertThat(status).as(path + " without a token").isIn(401, 404));
        }
        // the internal management port exposes health (with the probes), info and prometheus only
        management.get().uri("/actuator/health").exchange().expectStatus().is2xxSuccessful();
        management.get().uri("/actuator/health/liveness").exchange().expectStatus().is2xxSuccessful();
        management.get().uri("/actuator/health/readiness").exchange().expectStatus().is2xxSuccessful();
        management.get().uri("/actuator/info").exchange().expectStatus().isOk();
        for (String path : List.of("/actuator/env", "/actuator/metrics", "/actuator/loggers", "/actuator/heapdump",
                "/actuator/gateway/routes")) {
            management.get().uri(path).header("Authorization", "Bearer " + admin).exchange().expectStatus()
                    .value(status -> assertThat(status).as("management " + path).isIn(401, 403, 404));
        }
        // the load balancer's readiness check on the public port
        client.get().uri("/api/health/gateway").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        assertThat(lastService.get()).as("no actuator request reached a service").isNull();
    }

    // ------------------------------------------------------------------ correlation ids on the gateway's own answers

    @Test
    void responsesTheGatewayWritesItselfCarryTheCorrelationId() throws Exception {
        // 401 from the security chain without an id: one is created, echoed and written into the ProblemDetail
        EntityExchangeResult<String> anonymous = client.get().uri("/api/orders").exchange()
                .expectStatus().isUnauthorized().expectBody(String.class).returnResult();
        String generated = anonymous.getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(generated).matches("[0-9a-f-]{36}");
        assertThat(anonymous.getResponseBody()).contains("\"correlationId\":\"" + generated + "\"",
                "\"errorCode\":\"UNAUTHENTICATED\"");

        // 401 with the caller's id
        client.get().uri("/api/orders").header("X-Correlation-Id", "apit-probe-401-0001").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().values("X-Correlation-Id", values -> assertThat(values).containsExactly("apit-probe-401-0001"))
                .expectBody().jsonPath("$.correlationId").isEqualTo("apit-probe-401-0001")
                .jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED");

        // 403 at the edge (a customer on an admin path)
        client.get().uri("/api/admin/orders").header("X-Correlation-Id", "apit-probe-403-0001")
                .header("Authorization", "Bearer " + validToken(UUID.randomUUID(), "CUSTOMER")).exchange()
                .expectStatus().isForbidden()
                .expectHeader().values("X-Correlation-Id", values -> assertThat(values).containsExactly("apit-probe-403-0001"))
                .expectBody().jsonPath("$.correlationId").isEqualTo("apit-probe-403-0001")
                .jsonPath("$.errorCode").isEqualTo("FORBIDDEN");

        // the gateway's own health controller, and a 404 the gateway writes itself (no actuator on the public port)
        client.get().uri("/api/health/gateway").header("X-Correlation-Id", "apit-probe-12345").exchange()
                .expectStatus().isOk()
                .expectHeader().values("X-Correlation-Id", values -> assertThat(values).containsExactly("apit-probe-12345"));
        client.get().uri("/actuator/health").header("X-Correlation-Id", "apit-probe-12346").exchange()
                .expectStatus().isNotFound()
                .expectHeader().values("X-Correlation-Id", values -> assertThat(values).containsExactly("apit-probe-12346"));

        // an unsafe value is never echoed or written into JSON: a new id replaces it
        client.get().uri("/api/orders").header("X-Correlation-Id", "x\",\"errorCode\":\"forged").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value("X-Correlation-Id", value -> assertThat(value).matches("[0-9a-f-]{36}"))
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED");

        // routed: the service receives exactly the id the client gets back, also when the client sent none
        EntityExchangeResult<byte[]> routed = client.get().uri("/api/health/vendor").exchange()
                .expectStatus().isOk().expectBody().returnResult();
        assertThat(routed.getResponseHeaders().get("X-Correlation-Id")).hasSize(1);
        assertThat(lastHeaders.get("x-correlation-id"))
                .isEqualTo(routed.getResponseHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }
}
