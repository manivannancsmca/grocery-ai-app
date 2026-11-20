package com.grocery.payment;

import com.grocery.common.id.Ids;
import com.grocery.payment.event.OrderEventsListener;
import com.grocery.payment.gateway.Signatures;
import com.grocery.payment.service.PaymentExpiryPoller;
import com.grocery.payment.service.RefundRetryPoller;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MySQL 8.4 in a throw-away container; Razorpay faked by {@link FakeRazorpay} (Razorpay mode, test keys below);
 * Kafka listeners and the {@code @Scheduled} pollers off — tests hand {@code order.events} records to the listener
 * directly (routing data in the headers, as Debezium delivers them) and run the pollers themselves. Requests are
 * authenticated with Spring Security's test JWT support, so the real security chain and {@code @PreAuthorize} rules
 * run. The database is shared by all test classes: every test uses fresh random order and customer ids.
 *
 * <p>Simulator-mode tests extend {@link AbstractSimulatorIntegrationTest} (a second application context).
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "grocery.payment.pollers-enabled=false",
        "grocery.payment.razorpay.key-id=" + AbstractIntegrationTest.KEY_ID,
        "grocery.payment.razorpay.key-secret=" + AbstractIntegrationTest.KEY_SECRET,
        "grocery.payment.razorpay.webhook-secret=" + AbstractIntegrationTest.WEBHOOK_SECRET,
        "grocery.payment.http.read-timeout=5s",
        // the concurrency tests queue more threads than the pool has connections
        "spring.datasource.hikari.connection-timeout=30000",
        // Avro outbox payloads (ADR-0011) against an in-memory Schema Registry that registers the schemas itself
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    public static final String KEY_ID = "rzp_test_fake";
    public static final String KEY_SECRET = "fake_key_secret";
    public static final String WEBHOOK_SECRET = "fake_webhook_secret";
    /** Confluent's in-memory Schema Registry, one scope per service (shared by every client of this JVM). */
    public static final String SCHEMA_REGISTRY_URL = "mock://payment-service";

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    protected static final FakeRazorpay RAZORPAY = new FakeRazorpay();

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("grocery.payment.razorpay.base-url", RAZORPAY::baseUrl);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected OrderEventsListener orderListener;
    @Autowired
    protected PaymentExpiryPoller expiryPoller;
    @Autowired
    protected RefundRetryPoller refundPoller;
    @Autowired
    protected TestClock clock;
    @Autowired
    private Resilience4JCircuitBreakerFactory breakers;

    protected final UUID customer = UUID.randomUUID();
    protected final UUID otherCustomer = UUID.randomUUID();
    protected final UUID financeAdmin = UUID.randomUUID();

    @BeforeEach
    void cleanSlate() {
        RAZORPAY.reset();
        breakers.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(cb -> cb.reset());
    }

    @AfterEach
    void resetClock() {
        clock.reset();
        RAZORPAY.reset();
    }

    // ---- callers ------------------------------------------------------------------------------------------

    /** A request authenticated as {@code userId} holding {@code roles} (name and email claims like identity's). */
    protected static JwtRequestPostProcessor as(UUID userId, String... roles) {
        List<String> roleList = Arrays.asList(roles);
        return jwt()
                .jwt(token -> token.subject(userId.toString())
                        .claim("email", emailOf(userId))
                        .claim("name", nameOf(userId))
                        .claim("roles", roleList))
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    protected static JwtRequestPostProcessor customer(UUID userId) {
        return as(userId, "CUSTOMER");
    }

    protected static String emailOf(UUID userId) {
        return "user-" + userId.toString().substring(0, 8) + "@example.com";
    }

    protected static String nameOf(UUID userId) {
        return "User " + userId.toString().substring(0, 8);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- order.events -------------------------------------------------------------------------------------

    /** A record as Debezium's outbox router delivers it: routing data in the headers, the payload as the value. */
    protected ConsumerRecord<String, String> orderEvent(String eventType, String eventId, UUID orderId,
                                                        Map<String, Object> payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0L, orderId.toString(),
                json.writeValueAsString(payload));
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", "Order".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    protected static String orderNumber(UUID orderId) {
        return "GA-" + orderId.toString().replace("-", "").substring(24).toUpperCase();
    }

    /** OrderAwaitingPayment (contract §6) of {@code customerId}: 15-minute window, contact phone. */
    protected ConsumerRecord<String, String> awaitingPayment(String eventId, UUID orderId, UUID customerId,
                                                             String amount) {
        return awaitingPayment(eventId, orderId, customerId, amount, Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    protected ConsumerRecord<String, String> awaitingPayment(String eventId, UUID orderId, UUID customerId,
                                                             String amount, Instant expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", orderNumber(orderId));
        payload.put("customerId", customerId.toString());
        payload.put("amount", new BigDecimal(amount));
        payload.put("currency", "INR");
        payload.put("expiresAt", expiresAt.toString());
        payload.put("contactPhone", "+919876543210");
        return orderEvent("OrderAwaitingPayment", eventId, orderId, payload);
    }

    protected ConsumerRecord<String, String> orderCancelled(String eventId, UUID orderId, String previousStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", orderNumber(orderId));
        payload.put("orderType", "GROCERY");
        payload.put("customerId", customer.toString());
        payload.put("storeId", UUID.randomUUID().toString());
        payload.put("previousStatus", previousStatus);
        payload.put("reason", "CUSTOMER_CANCELLED");
        payload.put("cancelledBy", "CUSTOMER");
        payload.put("actorUserId", customer.toString());
        payload.put("note", null);
        payload.put("cancelledAt", Instant.now().toString());
        return orderEvent("OrderCancelled", eventId, orderId, payload);
    }

    protected static String newEventId() {
        return Ids.newId().toString();
    }

    /** A new order of {@link #customer} awaiting payment of {@code amount}; returns the order id. */
    protected UUID payableOrder(String amount) {
        UUID orderId = Ids.newId();
        orderListener.on(awaitingPayment(newEventId(), orderId, customer, amount));
        return orderId;
    }

    protected void cancelOrder(UUID orderId) {
        orderListener.on(orderCancelled(newEventId(), orderId, "AWAITING_PAYMENT"));
    }

    // ---- payment API --------------------------------------------------------------------------------------

    protected ResultActions checkoutRequest(UUID orderId, UUID customerId) throws Exception {
        return mvc.perform(get("/api/payments/orders/{orderId}/checkout", orderId).with(customer(customerId)));
    }

    protected JsonNode checkout(UUID orderId) throws Exception {
        return body(checkoutRequest(orderId, customer).andExpect(status().isOk()));
    }

    protected ResultActions verifyRequest(UUID customerId, String razorpayOrderId, String razorpayPaymentId,
                                          String signature) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("razorpayOrderId", razorpayOrderId);
        request.put("razorpayPaymentId", razorpayPaymentId);
        request.put("razorpaySignature", signature);
        return mvc.perform(post("/api/payments/razorpay/verify").with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    /** Verify with a correct signature (the provider's key secret, as Checkout signs it). */
    protected ResultActions verifySigned(UUID customerId, String razorpayOrderId, String razorpayPaymentId)
            throws Exception {
        return verifyRequest(customerId, razorpayOrderId, razorpayPaymentId,
                Signatures.checkout(razorpayOrderId, razorpayPaymentId, keySecret()));
    }

    protected String keySecret() {
        return KEY_SECRET;
    }

    protected String webhookSecret() {
        return WEBHOOK_SECRET;
    }

    protected JsonNode paymentView(UUID orderId) throws Exception {
        return body(mvc.perform(get("/api/payments/orders/{orderId}", orderId).with(customer(customer)))
                .andExpect(status().isOk()));
    }

    /** A Razorpay webhook as Razorpay sends it: signed raw body, event id header. */
    protected ResultActions webhook(String rawBody, String signature, String eventId) throws Exception {
        var request = post("/api/payments/razorpay/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            request.header("X-Razorpay-Signature", signature);
        }
        if (eventId != null) {
            request.header("X-Razorpay-Event-Id", eventId);
        }
        return mvc.perform(request);
    }

    protected ResultActions signedWebhook(String rawBody, String eventId) throws Exception {
        return webhook(rawBody, Signatures.hmacHex(webhookSecret(), rawBody.getBytes(StandardCharsets.UTF_8)),
                eventId);
    }

    /** {@code {"entity":"event","event":…,"payload":{"payment":{"entity":{…}}}}}, Razorpay's webhook shape. */
    protected String paymentWebhook(String event, String paymentId, String razorpayOrderId, String status,
                                    long amountPaise) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", paymentId);
        entity.put("entity", "payment");
        entity.put("amount", amountPaise);
        entity.put("currency", "INR");
        entity.put("status", status);
        entity.put("order_id", razorpayOrderId);
        entity.put("method", "upi");
        entity.put("error_code", "failed".equals(status) ? "BAD_REQUEST_ERROR" : null);
        entity.put("error_description", "failed".equals(status) ? "Payment declined by the bank" : null);
        return envelope(event, "payment", entity);
    }

    protected String refundWebhook(String event, String refundId, String paymentId, long amountPaise,
                                   String status, UUID ourRefundId) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", refundId);
        entity.put("entity", "refund");
        entity.put("amount", amountPaise);
        entity.put("currency", "INR");
        entity.put("payment_id", paymentId);
        entity.put("notes", ourRefundId == null ? Map.of() : Map.of("refundId", ourRefundId.toString()));
        entity.put("status", status);
        return envelope(event, "refund", entity);
    }

    private String envelope(String event, String name, Map<String, Object> entity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entity", "event");
        body.put("account_id", "acc_test");
        body.put("event", event);
        body.put("contains", List.of(name));
        body.put("payload", Map.of(name, Map.of("entity", entity)));
        body.put("created_at", Instant.now().getEpochSecond());
        return json.writeValueAsString(body);
    }

    protected static String webhookEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    // ---- database -----------------------------------------------------------------------------------------

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    protected Map<String, Object> paymentRow(UUID orderId) {
        return jdbc().sql("""
                        SELECT BIN_TO_UUID(id) AS id, status, amount, refunded_amount, refund_status, razorpay_order_id,
                               razorpay_payment_id, method, late_capture, expires_at, captured_at, contact_phone,
                               poll_lease_until
                        FROM payment WHERE order_id = UUID_TO_BIN(?)""")
                .param(orderId.toString()).query().singleRow();
    }

    protected String paymentStatus(UUID orderId) {
        return (String) paymentRow(orderId).get("status");
    }

    protected String razorpayOrderId(UUID orderId) {
        return (String) paymentRow(orderId).get("razorpay_order_id");
    }

    protected long paymentCount(UUID orderId) {
        return jdbc().sql("SELECT COUNT(*) FROM payment WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(Long.class).single();
    }

    /** Moves the end of the payment window into the past (the poller then sees it as over). */
    protected void endWindow(UUID orderId) {
        jdbc().sql("UPDATE payment SET expires_at = ? WHERE order_id = UUID_TO_BIN(?)")
                .param(java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(60), java.time.ZoneOffset.UTC))
                .param(orderId.toString()).update();
    }

    /** Every outbox event about one order (aggregate Payment), as "eventType", oldest first. */
    protected List<String> eventTypes(UUID orderId) {
        return jdbc().sql("SELECT event_type FROM outbox_event WHERE aggregate_type = 'Payment' "
                        + "AND aggregate_id = ? ORDER BY occurred_at, id")
                .param(orderId.toString()).query(String.class).list();
    }

    /** Payloads of an order's outbox events of one type, oldest first. */
    protected List<JsonNode> events(UUID orderId, String eventType) {
        return jdbc()
                .sql("SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ? ORDER BY occurred_at, id")
                .param(orderId.toString()).param(eventType)
                .query(String.class).list().stream()
                .map(json::readTree)
                .toList();
    }

    protected JsonNode event(UUID orderId, String eventType) {
        List<JsonNode> found = events(orderId, eventType);
        if (found.size() != 1) {
            throw new AssertionError("Expected one " + eventType + " for order " + orderId + " but found "
                    + found.size() + ": " + eventTypes(orderId));
        }
        return found.getFirst();
    }

    protected List<Map<String, Object>> refundRows(UUID orderId) {
        return jdbc().sql("""
                        SELECT BIN_TO_UUID(id) AS id, amount, reason, status, razorpay_refund_id, failure_code,
                               provider_attempts, next_attempt_at, lease_until
                        FROM refund WHERE order_id = UUID_TO_BIN(?) ORDER BY created_at, id""")
                .param(orderId.toString()).query().listOfRows();
    }

    protected List<Map<String, Object>> attemptRows(UUID orderId) {
        return jdbc().sql("""
                        SELECT a.razorpay_payment_id, a.status, a.error_code FROM payment_attempt a
                        JOIN payment p ON p.id = a.payment_id
                        WHERE p.order_id = UUID_TO_BIN(?) ORDER BY a.created_at, a.id""")
                .param(orderId.toString()).query().listOfRows();
    }
}
