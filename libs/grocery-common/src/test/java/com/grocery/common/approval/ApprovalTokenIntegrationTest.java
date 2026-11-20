package com.grocery.common.approval;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The target side of an approved action end to end: a real servlet application (embedded Tomcat, grocery-common's
 * JWT resource server, Spring MVC) as order-service, MySQL 8.4 with {@code consumed_approval}, and a stub of the
 * orchestrator's JWKS. Requests carry the approver's access token and an approval token, exactly as the orchestrator
 * sends them.
 */
@Testcontainers
@DirtiesContext
@SpringBootTest(classes = ApprovalTokenIntegrationTest.OrderServiceApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.application.name=order-service",
        "grocery.approvals.enabled=true",
        "grocery.approvals.jwks-min-refetch-interval=0s",
        "grocery.events.avro.enabled=false",
        "grocery.dead-letters.enabled=false",
        "grocery.housekeeping.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
})
class ApprovalTokenIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    static final RSAKey ACCESS_KEY = rsaKey("identity-1");
    static final TestApprovalSigner ORCHESTRATOR = TestApprovalSigner.generate("orchestrator-1");
    static final TestApprovalSigner ROTATED = TestApprovalSigner.generate("orchestrator-2");
    static final TestApprovalSigner UNKNOWN = TestApprovalSigner.generate("orchestrator-x");
    static final StubJwks JWKS = StubJwks.start(ORCHESTRATOR.publicKey());

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ORDER = "{\"addressId\":\"0190f3a2-0000-7000-8000-0000000000aa\",\"note\":\"Ring twice ✓\","
            + "\"expectedTotal\":225.00,\"expectedCartUpdatedAt\":\"2026-11-13T09:59:12.123456Z\"}";

    final UUID customer = UUID.randomUUID();
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;
    @Autowired
    DataSource dataSource;
    @Autowired
    Handlers handlers;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("APPROVAL_JWKS_URI", JWKS::uri);             // what Docker Compose sets for the target services
    }

    /** order-service in miniature: grocery-common's auto-configuration plus two approval-protected endpoints. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Handlers.class)
    static class OrderServiceApp {

        @Bean
        JwtDecoder jwtDecoder() throws JOSEException {
            return NimbusJwtDecoder.withPublicKey(ACCESS_KEY.toRSAPublicKey()).build();
        }
    }

    @RestController
    public static class Handlers {

        final AtomicInteger calls = new AtomicInteger();

        @PostMapping({"/api/orders", "/api/orders/{orderId}/cancel"})
        public Map<String, Object> handle(@RequestBody(required = false) String body, HttpServletRequest request) {
            calls.incrementAndGet();
            Map<String, Object> seen = new LinkedHashMap<>();
            seen.put("body", body);
            ApprovalContext.from(request).ifPresent(approval -> {
                seen.put("approvalId", approval.approvalId().toString());
                seen.put("action", approval.action());
                seen.put("approverId", approval.approverId().toString());
                seen.put("onBehalfOf", approval.onBehalfOf() == null ? null : approval.onBehalfOf().toString());
                seen.put("current", ApprovalContext.current().orElse(null) == approval);
            });
            return seen;
        }
    }

    record Answer(int status, String contentType, Map<String, Object> json) {
    }

    @BeforeEach
    void freshTable() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS consumed_approval (
                    approval_id BINARY(16)  NOT NULL,
                    action      VARCHAR(40) NOT NULL,
                    approver_id BINARY(16)  NOT NULL,
                    consumed_at DATETIME(6) NOT NULL,
                    CONSTRAINT pk_consumed_approval PRIMARY KEY (approval_id),
                    INDEX idx_consumed_approval_consumed_at (consumed_at)
                ) ENGINE = InnoDB""").update();
        jdbc.sql("DELETE FROM consumed_approval").update();
        handlers.calls.set(0);
    }

    @AfterEach
    void restoreKeys() {
        JWKS.down(false);
        JWKS.keys(ORCHESTRATOR.publicKey());
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    private static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An identity-service access token of {@code user}. */
    private static String accessToken(UUID user, String role) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(ACCESS_KEY.getKeyID()).build(),
                    new JWTClaimsSet.Builder().subject(user.toString()).claim("roles", List.of(role))
                            .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build());
            jwt.sign(new RSASSASigner(ACCESS_KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestApprovalSigner.Token placeOrder() {
        return ORCHESTRATOR.token(customer).body(ORDER);
    }

    private Answer post(String pathAndQuery, String body, UUID user, String approvalToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (user != null) {
            request.header("Authorization", "Bearer " + accessToken(user, "CUSTOMER"));
        }
        if (approvalToken != null) {
            request.header(ApprovalToken.HEADER, approvalToken);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> json = response.body().isEmpty() ? Map.of() : JSON.readValue(response.body(), Map.class);
        return new Answer(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), json);
    }

    private Answer place(String token) throws Exception {
        return post("/api/orders", ORDER, customer, token);
    }

    private long consumedRows() {
        return JdbcClient.create(dataSource).sql("SELECT COUNT(*) FROM consumed_approval").query(Long.class).single();
    }

    private static void assertRefused(Answer answer, int status, String errorCode, String reason) {
        assertThat(answer.status()).isEqualTo(status);
        assertThat(answer.contentType()).startsWith("application/problem+json");
        assertThat(answer.json()).containsEntry("errorCode", errorCode).containsEntry("status", status);
        if (reason == null) {
            assertThat(answer.json()).doesNotContainKey("reason");
        } else {
            assertThat(answer.json()).containsEntry("reason", reason);
        }
    }

    private void assertNothingRan() {
        assertThat(handlers.calls).hasValue(0);
        assertThat(consumedRows()).isZero();
    }

    @Test
    void aValidTokenRunsTheHandlerOnceWithTheBodyIntactAndTheApprovalAttribute() throws Exception {
        TestApprovalSigner.Token token = placeOrder();
        String signed = token.sign();

        Answer first = place(signed);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json()).containsEntry("body", ORDER)
                .containsEntry("approvalId", token.id().toString())
                .containsEntry("action", "PLACE_ORDER")
                .containsEntry("approverId", customer.toString())
                .containsEntry("onBehalfOf", customer.toString())
                .containsEntry("current", true);
        Map<String, Object> row = JdbcClient.create(dataSource).sql("""
                        SELECT BIN_TO_UUID(approver_id) AS approver, action, consumed_at FROM consumed_approval
                        WHERE approval_id = UUID_TO_BIN(?)""").param(token.id().toString())
                .query().singleRow();
        assertThat(row).containsEntry("approver", customer.toString()).containsEntry("action", "PLACE_ORDER");
        assertThat(handlers.calls).hasValue(1);

        Answer second = place(signed);
        assertRefused(second, 409, "APPROVAL_ALREADY_USED", null);
        assertThat(second.json()).containsEntry("detail", "This approval has already been used.");
        assertThat(handlers.calls).hasValue(1);
        assertThat(consumedRows()).isEqualTo(1);
    }

    @Test
    void concurrentUsesOfOneApprovalRunTheHandlerExactlyOnce() throws Exception {
        String signed = placeOrder().sign();
        int parallel = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            List<Future<Answer>> answers = new ArrayList<>();
            for (int i = 0; i < parallel; i++) {
                Callable<Answer> call = () -> {
                    start.await();
                    return place(signed);
                };
                answers.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Answer> answer : answers) {
                statuses.add(answer.get().status());
            }
            assertThat(statuses).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(handlers.calls).hasValue(1);
        assertThat(consumedRows()).isEqualTo(1);
    }

    @Test
    void aTamperedBodyIsRefusedAndNothingRuns() throws Exception {
        String signed = placeOrder().sign();
        assertRefused(post("/api/orders", ORDER.replace("225.00", "2.25"), customer, signed), 403,
                "APPROVAL_BODY_MISMATCH", null);
        assertRefused(post("/api/orders", ORDER + "\n", customer, signed), 403, "APPROVAL_BODY_MISMATCH", null);
        assertRefused(post("/api/orders", null, customer, signed), 403, "APPROVAL_BODY_MISMATCH", null);
        assertNothingRan();

        assertThat(place(signed).status()).isEqualTo(200);         // the untouched request still works
    }

    @Test
    void anEmptyBodyIsBoundToo() throws Exception {
        String path = "/api/orders/0190f3a2-0000-7000-8000-000000000123/cancel";
        TestApprovalSigner.Token cancel = ORCHESTRATOR.token(customer).action("CANCEL_ORDER").request("POST", path);
        Answer answer = post(path, null, customer, cancel.sign());
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.json()).containsEntry("body", null).containsEntry("action", "CANCEL_ORDER");

        TestApprovalSigner.Token another = ORCHESTRATOR.token(customer).action("CANCEL_ORDER").request("POST", path);
        assertRefused(post(path, "{\"reason\":\"changed my mind\"}", customer, another.sign()), 403,
                "APPROVAL_BODY_MISMATCH", null);
    }

    @Test
    void anotherPathMethodOrQueryIsRefused() throws Exception {
        String signed = placeOrder().sign();
        assertRefused(post("/api/orders/0190f3a2-0000-7000-8000-000000000123/cancel", ORDER, customer, signed), 403,
                "APPROVAL_TOKEN_INVALID", "METHOD_OR_PATH");
        assertRefused(post("/api/orders?expectedTotal=1", ORDER, customer, signed), 403, "APPROVAL_TOKEN_INVALID",
                "METHOD_OR_PATH");
        assertRefused(place(placeOrder().request("PUT", "/api/orders").sign()), 403, "APPROVAL_TOKEN_INVALID",
                "METHOD_OR_PATH");
        assertNothingRan();
    }

    @Test
    void aTokenForAnotherServiceIsRefused() throws Exception {
        Answer answer = place(placeOrder().audiences("payment-service").sign());
        assertRefused(answer, 403, "APPROVAL_TOKEN_INVALID", "AUDIENCE");
        assertThat(answer.json()).containsEntry("detail", "The approval token is not meant for this service.");
        assertNothingRan();
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        assertRefused(place(placeOrder().issuedAt(Instant.now().minusSeconds(70)).sign()), 403,
                "APPROVAL_TOKEN_INVALID", "EXPIRED");
        assertNothingRan();
    }

    @Test
    void anUnknownKeyIdRefetchesTheKeysThenFailsTheSignature() throws Exception {
        assertThat(place(placeOrder().sign()).status()).isEqualTo(200);      // keys cached
        handlers.calls.set(0);
        int fetches = JWKS.fetches();

        assertRefused(place(UNKNOWN.token(customer).body(ORDER).sign()), 403, "APPROVAL_TOKEN_INVALID", "SIGNATURE");
        assertThat(JWKS.fetches()).isEqualTo(fetches + 1);
        assertThat(handlers.calls).hasValue(0);

        JWKS.keys(ORCHESTRATOR.publicKey(), ROTATED.publicKey());              // the orchestrator rotates its key
        assertThat(place(ROTATED.token(customer).body(ORDER).sign()).status()).isEqualTo(200);
        assertThat(JWKS.fetches()).isEqualTo(fetches + 2);
    }

    @Test
    void anotherUserThanTheApproverIsRefused() throws Exception {
        TestApprovalSigner.Token token = placeOrder();
        assertRefused(post("/api/orders", ORDER, UUID.randomUUID(), token.sign()), 403, "APPROVAL_TOKEN_INVALID",
                "SUBJECT");
        assertNothingRan();
    }

    @Test
    void keysThatCannotBeLoadedAre503() throws Exception {
        JWKS.keys(ORCHESTRATOR.publicKey(), UNKNOWN.publicKey());
        JWKS.down(true);
        String signed = UNKNOWN.token(customer).body(ORDER).sign();
        Answer answer = place(signed);
        assertRefused(answer, 503, "APPROVAL_KEYS_UNAVAILABLE", null);
        assertNothingRan();

        JWKS.down(false);                                                       // back: the same approval runs
        assertThat(place(signed).status()).isEqualTo(200);
        assertThat(handlers.calls).hasValue(1);
    }

    @Test
    void withoutTheHeaderTheRequestIsUntouched() throws Exception {
        int fetches = JWKS.fetches();
        Answer answer = place(null);
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.json()).containsOnlyKeys("body").containsEntry("body", ORDER);
        assertThat(handlers.calls).hasValue(1);
        assertThat(consumedRows()).isZero();
        assertThat(JWKS.fetches()).isEqualTo(fetches);
    }

    @Test
    void theCallerIsAuthenticatedAndAuthorisedFirst() throws Exception {
        Answer answer = post("/api/orders", ORDER, null, placeOrder().sign());
        assertThat(answer.status()).isEqualTo(401);
        assertThat(answer.json()).containsEntry("errorCode", "UNAUTHENTICATED");
        assertNothingRan();
    }
}
