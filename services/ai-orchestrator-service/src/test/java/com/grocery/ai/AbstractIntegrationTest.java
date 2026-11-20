package com.grocery.ai;

import com.grocery.ai.approval.ApprovalAction;
import com.grocery.ai.approval.ApprovalMaintenance;
import com.grocery.ai.approval.ApprovalService;
import com.grocery.ai.approval.ApprovalView;
import com.grocery.ai.approval.ProposalDraft;
import com.grocery.common.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The real server on a random port with real MySQL 8.4 (Testcontainers, shared by all test classes in one context),
 * the business APIs behind a {@link FakeGateway}, the scripted model by default ({@link TestModels.Switchable}), the
 * outbox encoding Avro against Confluent's in-memory Schema Registry, and the approval poller run by the tests.
 * The chat stream is read over real HTTP with real signed tokens ({@link TestTokens}); other endpoints go through
 * MockMvc with Spring Security's test JWT (the real security chain and {@code @PreAuthorize} rules run). Tests use
 * fresh random user ids and look at their own rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "grocery.ai.provider=scripted",
        "grocery.ai.scripted.allow-any-profile=true",
        // the scripted model answers "add 2 rice" itself; ShoppingCommandsIntegrationTest turns the commands on
        "grocery.ai.shopping-commands=false",
        // the business APIs are the FakeGateway's REST stubs; McpPilotIntegrationTest turns the MCP servers on
        "grocery.ai.mcp.enabled=false",
        "grocery.ai.approvals.pollers-enabled=false",
        "grocery.ai.model-timeout=4s",
        "grocery.ai.approvals.execution-timeout=2s",
        // the concurrency tests queue more threads than the pool has connections
        "spring.datasource.hikari.connection-timeout=30000",
        // Avro outbox payloads (ADR-0011) against an in-memory Schema Registry that registers the schemas itself
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true",
        // Voice through an OpenAI-compatible audio API, answered by the FakeGateway under /v1/audio/**
        "grocery.ai.voice.mode=server",
        "grocery.ai.voice.api-key=test-voice-key",
        "grocery.ai.voice.timeout=2s",
        "grocery.ai.voice.max-audio-bytes=200000",
        "grocery.ai.voice.max-speech-characters=500"
})
@AutoConfigureMockMvc
@Import(TestConfig.class)
public abstract class AbstractIntegrationTest {

    public static final String SCHEMA_REGISTRY_URL = "mock://ai-orchestrator-service";

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    protected static final FakeGateway GATEWAY = new FakeGateway();
    protected static final TestTokens TOKENS = TestTokens.start();

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("grocery.ai.gateway-url", GATEWAY::baseUrl);
        registry.add("grocery.ai.voice.base-url", () -> GATEWAY.baseUrl() + "/v1");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwksUri);
    }

    @Value("${local.server.port}")
    protected int port;
    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected TestClock clock;
    @Autowired
    protected TestModels.Switchable model;
    @Autowired
    protected ApprovalService approvals;
    @Autowired
    protected ApprovalMaintenance maintenance;

    @BeforeEach
    void cleanSlate() {
        GATEWAY.reset();
        model.reset();
        clock.reset();
    }

    @AfterEach
    void restore() {
        GATEWAY.reset();
        model.reset();
        clock.reset();
    }

    // ---- callers ------------------------------------------------------------------------------------------------

    /** A MockMvc request authenticated as {@code userId} holding {@code roles}. */
    protected static JwtRequestPostProcessor as(UUID userId, String... roles) {
        List<String> roleList = Arrays.asList(roles);
        return jwt()
                .jwt(token -> token.tokenValue("token-of-" + userId).subject(userId.toString())
                        .claim("email", "user@example.com")
                        .claim("name", "Asha Rao")
                        .claim("roles", roleList))
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    protected static CurrentUser user(UUID userId, String... roles) {
        return new CurrentUser(userId, "user@example.com", "Asha Rao", Set.of(roles), null, null);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- chat over real HTTP --------------------------------------------------------------------------------------

    protected SseClient.Result chat(String token, String agent, String message) {
        return chat(token, agent, message, null);
    }

    protected SseClient.Result chat(String token, String agent, String message, UUID conversationId) {
        Map<String, Object> request = new TreeMap<>();
        request.put("message", message);
        if (conversationId != null) {
            request.put("conversationId", conversationId.toString());
        }
        return SseClient.post(port, "/api/ai/agents/" + agent + "/chat", token, json.writeValueAsString(request),
                "text/event-stream");
    }

    // ---- fake business APIs ---------------------------------------------------------------------------------------

    /** The customer's cart, its preview and addresses. */
    protected void stubCart(Map<String, Object> cart) {
        GATEWAY.json("GET", "/api/cart", 200, Fixtures.json(cart));
    }

    protected void stubDefaultCartAndPreview() {
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/cart/preview", 200, Fixtures.preview(true));
    }

    // ---- approvals ------------------------------------------------------------------------------------------------

    /** A PLACE_ORDER request of {@code customer} as the proposal would build it (body as given). */
    protected ApprovalView proposeOrder(UUID customer, String total) {
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("addressId", Fixtures.ADDRESS.toString());
        body.put("expectedCartUpdatedAt", Fixtures.CART_UPDATED_AT);
        body.put("expectedTotal", new BigDecimal(total));
        body.put("note", "Ring twice " + UUID.randomUUID());
        return approvals.propose(new ApprovalService.Origin("shopping", null, null, user(customer, "CUSTOMER"),
                        "You asked me to check out."),
                new ProposalDraft(ApprovalAction.PLACE_ORDER, customer, "POST", "/api/orders", body,
                        "Place order: 3 items from Fresh Mart", List.of(new ApprovalView.Line("Total", "₹" + total)),
                        new BigDecimal(total), "INR"));
    }

    /** A REFUND_PAYMENT request proposed by {@code admin}. */
    protected ApprovalView proposeRefund(UUID admin, String role, String amount) {
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("amount", new BigDecimal(amount));
        body.put("note", "Damaged items " + UUID.randomUUID());
        body.put("orderId", UUID.randomUUID().toString());
        ApprovalView view = approvals.propose(new ApprovalService.Origin("admin-bi", null, null, user(admin, role),
                        null),
                new ProposalDraft(ApprovalAction.REFUND_PAYMENT, null, "POST", "/api/admin/refunds", body,
                        "Refund ₹" + amount + " for order GA-TEST", List.of(new ApprovalView.Line("Refund amount",
                        "₹" + amount)), new BigDecimal(amount), "INR"));
        return view;
    }

    /** A SUSPEND_VENDOR request proposed by {@code admin}. */
    protected ApprovalView proposeSuspension(UUID admin, UUID vendorId) {
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("reason", "Repeated hygiene complaints");
        return approvals.propose(new ApprovalService.Origin("admin-bi", null, null, user(admin, "OPS_ADMIN"), null),
                new ProposalDraft(ApprovalAction.SUSPEND_VENDOR, null, "POST",
                        "/api/admin/vendors/" + vendorId + "/suspend", body, "Suspend vendor Fresh Mart",
                        List.of(new ApprovalView.Line("Vendor", "Fresh Mart")), null, null));
    }

    // ---- assertions helpers ---------------------------------------------------------------------------------------

    /** An ApprovalView's summary lines as "label=value". */
    protected static List<String> summaryLines(JsonNode view) {
        return view.get("summary").get("lines").valueStream()
                .map(line -> line.get("label").asString() + "=" + line.get("value").asString()).toList();
    }

    /** The claims of the X-Approval-Token an approved call carried. */
    protected static JWTClaimsSet approvalClaims(FakeGateway.Request call) throws Exception {
        return SignedJWT.parse(call.header("X-Approval-Token")).getJWTClaimsSet();
    }

    protected static String bodyHash(byte[] body) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(body));
    }

    /** {@code expires_at − created_at} of an approval row. */
    protected static Duration lifetime(Map<String, Object> row) {
        return Duration.between(instant(row.get("created_at")), instant(row.get("expires_at")));
    }

    private static Instant instant(Object value) {
        return value instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC)
                : ((Timestamp) value).toInstant();
    }

    /** "tool:STATUS" of every tool event of a turn. */
    protected static List<String> toolEvents(SseClient.Result result) {
        return result.named("tool").stream().map(e -> e.text("tool") + ":" + e.text("status")).toList();
    }

    // ---- database -------------------------------------------------------------------------------------------------

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    protected Map<String, Object> approvalRow(UUID id) {
        return jdbc().sql("""
                        SELECT action, status, http_method, http_path, request_body, body_sha256, amount, four_eyes,
                               BIN_TO_UUID(approver_user_id) AS approver_user_id, approver_roles,
                               BIN_TO_UUID(on_behalf_of) AS on_behalf_of, BIN_TO_UUID(decided_by) AS decided_by,
                               failure_code, result_reference, expires_at, created_at, version
                        FROM approval_request WHERE id = UUID_TO_BIN(?)""")
                .param(id.toString()).query().singleRow();
    }

    /** Event types of one approval in the outbox, oldest first. */
    protected List<String> eventTypes(UUID approvalId) {
        return jdbc().sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY occurred_at, id")
                .param(approvalId.toString()).query(String.class).list();
    }

    protected JsonNode event(UUID approvalId, String eventType) {
        List<String> payloads = jdbc().sql("SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ?")
                .param(approvalId.toString()).param(eventType).query(String.class).list();
        if (payloads.size() != 1) {
            throw new AssertionError("Expected one " + eventType + " for " + approvalId + ", found "
                    + eventTypes(approvalId));
        }
        return json.readTree(payloads.getFirst());
    }

    /** Audit rows of one user, oldest first, as maps (tool, status, reason, …). */
    protected List<Map<String, Object>> auditRows(UUID userId) {
        return jdbc().sql("""
                        SELECT tool, status, reason, agent, arguments, result_summary, http_status, page_links,
                               transport, BIN_TO_UUID(approval_id) AS approval_id,
                               BIN_TO_UUID(message_id) AS message_id, BIN_TO_UUID(conversation_id) AS conversation_id
                        FROM ai_tool_invocation WHERE user_id = UUID_TO_BIN(?) ORDER BY created_at, id""")
                .param(userId.toString()).query().listOfRows();
    }
}
