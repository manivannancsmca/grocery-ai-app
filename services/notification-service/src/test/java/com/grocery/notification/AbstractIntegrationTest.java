package com.grocery.notification;

import com.grocery.notification.config.NotificationProperties;
import com.grocery.notification.event.DispatchListener;
import com.grocery.notification.realtime.ConnectionRegistry;
import com.grocery.notification.repository.NotificationRepository;
import com.grocery.notification.repository.UuidBytes;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real server on a random port, real MySQL 8.4, Redis 8.8 and (Phase 12) Mailpit in throw-away containers, shared
 * by all test classes (one Spring context). The email channel is on (its outbox is written), its background sender is
 * off: email tests run the sender themselves. Kafka listeners do not start: records carrying the outbox router's headers are handed to the
 * listener directly, as Kafka would. Inbox calls go through MockMvc with Spring Security's test JWT (the real
 * security chain and rules run); the SSE stream is read over real HTTP with real signed tokens validated against a
 * stub JWKS ({@link TestTokens}). Every message published on the Redis channel is recorded ({@link RealtimeRecorder}).
 * The database is shared: tests use random ids only and look at their own rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        // ADR-0011: consumers decode Avro against the in-memory Schema Registry
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true",
        // fast heartbeats (also detect closed clients quickly) and a small replay window
        "grocery.notification.heartbeat-interval=1s",
        "grocery.notification.replay-limit=5",
        "grocery.notification.instance-id=replica-a",
        // Phase 12: the email channel writes its outbox; tests run the sender themselves (no background job)
        "grocery.notification.email.enabled=true",
        "grocery.notification.email.sender-enabled=false",
        "grocery.notification.email.app-base-url=https://shop.grocery.test/"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    /** Confluent's in-memory Schema Registry, one scope per service (shared by every client of this JVM). */
    public static final String SCHEMA_REGISTRY_URL = "mock://notification-service";
    static final Duration WAIT = Duration.ofSeconds(10);

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);
    /** Phase 12: the local SMTP catcher of compose (SMTP 1025, HTTP API 8025). */
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse(MailpitClient.IMAGE))
            .withExposedPorts(MailpitClient.SMTP_PORT, MailpitClient.HTTP_PORT)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(MailpitClient.HTTP_PORT));
    static final TestTokens TOKENS = TestTokens.start();

    static {
        Startables.deepStart(MYSQL, REDIS, MAILPIT).join();
    }

    /** Where a replica finds MySQL, Redis and the JWKS (also used to start a second replica). */
    static Map<String, String> infrastructure() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("spring.datasource.url", MYSQL.getJdbcUrl());
        settings.put("spring.datasource.username", MYSQL.getUsername());
        settings.put("spring.datasource.password", MYSQL.getPassword());
        settings.put("spring.data.redis.host", REDIS.getHost());
        settings.put("spring.data.redis.port", String.valueOf(REDIS.getMappedPort(6379)));
        settings.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS.jwksUri());
        settings.put("spring.mail.host", MAILPIT.getHost());
        settings.put("spring.mail.port", String.valueOf(MAILPIT.getMappedPort(MailpitClient.SMTP_PORT)));
        return settings;
    }

    /** Phase 12: Mailpit's HTTP API (the messages it caught). */
    protected static MailpitClient mailpit() {
        return new MailpitClient("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(MailpitClient.HTTP_PORT));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        infrastructure().forEach((name, value) -> registry.add(name, () -> value));
    }

    /** One recorder per application context, subscribed to the realtime channel like any replica. */
    private static final Map<RedisMessageListenerContainer, RealtimeRecorder> RECORDERS = new ConcurrentHashMap<>();

    @Value("${local.server.port}")
    protected int port;
    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected DispatchListener listener;
    @Autowired
    protected ConnectionRegistry registry;
    @Autowired
    private RedisMessageListenerContainer redisListeners;
    @Autowired
    private NotificationProperties properties;
    /** The real repository; one stream test runs events "during" the replay query. */
    @MockitoSpyBean
    protected NotificationRepository notificationRepository;

    protected JdbcClient jdbc;
    /** Every message published on the realtime channel since the context started. */
    protected RealtimeRecorder recorder;
    private final List<SseClient> streams = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        recorder = RECORDERS.computeIfAbsent(redisListeners, container -> {
            RealtimeRecorder created = new RealtimeRecorder(json);
            container.addMessageListener(created, new ChannelTopic(properties.getRedisChannel()));
            return created;
        });
    }

    @AfterEach
    void closeStreams() {
        streams.forEach(SseClient::close);
        streams.clear();
    }

    // ---------------------------------------------------------------- events

    /** Hands the record to the Kafka listener, as the container would. */
    protected void dispatch(ConsumerRecord<String, String> record) {
        listener.on(record);
    }

    /** The owner of an approved vendor, known to the projection (an old event: no notification, no signal). */
    protected UUID knownVendor(UUID vendorId, String businessName) {
        UUID owner = UUID.randomUUID();
        dispatch(TestEvents.old(TestEvents.vendor("VendorApproved", vendorId, owner, businessName, null)));
        return owner;
    }

    protected UUID knownRestaurant(UUID restaurantId, String name) {
        UUID owner = UUID.randomUUID();
        dispatch(TestEvents.old(TestEvents.restaurant("RestaurantApproved", restaurantId, owner, name, null)));
        return owner;
    }

    /** The order is known to the projection (an old OrderCreated: no signal). */
    protected void knownOrder(TestEvents.Order order) {
        dispatch(TestEvents.old(TestEvents.orderCreated(order)));
    }

    // ---------------------------------------------------------------- stored notifications

    /** A stored notification with its audience keys. */
    protected record Stored(UUID id, String type, String category, String severity, String title, String body,
                            String link, JsonNode data, Set<String> audiences) {
    }

    /** Every notification the event created, oldest first. */
    protected List<Stored> notificationsOf(ConsumerRecord<String, String> record) {
        return jdbc.sql("""
                        SELECT n.id, n.type, n.category, n.severity, n.title, n.body, n.link, n.data,
                               GROUP_CONCAT(a.audience_key ORDER BY a.audience_key SEPARATOR ',') AS audiences
                        FROM notification n JOIN notification_audience a ON a.notification_id = n.id
                        WHERE n.source_event_id = :eventId
                        GROUP BY n.id ORDER BY n.id""")
                .param("eventId", TestEvents.eventId(record))
                .query((rs, rowNum) -> new Stored(UuidBytes.uuid(rs.getBytes("id")), rs.getString("type"),
                        rs.getString("category"), rs.getString("severity"), rs.getString("title"),
                        rs.getString("body"), rs.getString("link"), json.readTree(rs.getString("data")),
                        new LinkedHashSet<>(Arrays.asList(rs.getString("audiences").split(",")))))
                .list();
    }

    /** The one notification of this type the event created. */
    protected Stored notificationOf(ConsumerRecord<String, String> record, String type) {
        List<Stored> found = notificationsOf(record).stream().filter(n -> n.type().equals(type)).toList();
        assertThat(found).as("%s notifications of %s", type, TestEvents.eventId(record)).hasSize(1);
        return found.getFirst();
    }

    protected boolean processed(ConsumerRecord<String, String> record) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE event_id = :id AND consumer = :consumer")
                .param("id", TestEvents.eventId(record))
                .param("consumer", "notification-service.dispatch")
                .query(Long.class).single() == 1;
    }

    protected Optional<Map<String, Object>> storeOwnerRow(String storeType, UUID storeId) {
        return jdbc.sql("SELECT owner_user_id, store_name FROM store_owner WHERE store_type = :type AND store_id = :id")
                .param("type", storeType)
                .param("id", UuidBytes.bytes(storeId))
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "owner", UuidBytes.uuid(rs.getBytes("owner_user_id")),
                        "name", String.valueOf(rs.getString("store_name"))))
                .optional();
    }

    protected Optional<Map<String, Object>> orderRefRow(UUID orderId) {
        return jdbc.sql("""
                        SELECT order_number, order_type, customer_id, store_id, store_name, total, status
                        FROM order_ref WHERE order_id = :id""")
                .param("id", UuidBytes.bytes(orderId))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("orderNumber", rs.getString("order_number"));
                    row.put("orderType", rs.getString("order_type"));
                    row.put("customerId", UuidBytes.uuid(rs.getBytes("customer_id")));
                    row.put("storeId", UuidBytes.uuid(rs.getBytes("store_id")));
                    row.put("storeName", rs.getString("store_name"));
                    row.put("total", rs.getBigDecimal("total"));
                    row.put("status", rs.getString("status"));
                    return row;
                })
                .optional();
    }

    // ---------------------------------------------------------------- inbox (MockMvc)

    /** A request authenticated as {@code userId} holding {@code roles}. */
    protected static JwtRequestPostProcessor as(UUID userId, String... roles) {
        List<String> roleList = Arrays.asList(roles);
        return jwt()
                .jwt(token -> token.subject(userId.toString())
                        .claim("email", "user-" + userId.toString().substring(0, 8) + "@example.com")
                        .claim("name", "User " + userId.toString().substring(0, 8))
                        .claim("roles", roleList))
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** The first 50 inbox entries of the caller, newest first. */
    protected List<JsonNode> inbox(UUID userId, String... roles) throws Exception {
        JsonNode page = body(mvc.perform(get("/api/notifications").param("size", "50").with(as(userId, roles)))
                .andExpect(status().isOk()));
        List<JsonNode> content = new ArrayList<>();
        page.get("content").forEach(content::add);
        return content;
    }

    /** The caller's inbox entries whose data mentions this id. */
    protected List<JsonNode> inboxAbout(Object id, UUID userId, String... roles) throws Exception {
        String text = String.valueOf(id);
        return inbox(userId, roles).stream().filter(n -> n.get("data").toString().contains(text)).toList();
    }

    // ---------------------------------------------------------------- streams

    protected String token(UUID userId, String... roles) {
        return TOKENS.token(userId, roles);
    }

    protected SseClient stream(String token) {
        return stream(token, null);
    }

    protected SseClient stream(String token, String lastEventId) {
        return track(SseClient.connect(port, token, lastEventId));
    }

    protected SseClient track(SseClient client) {
        streams.add(client);
        return client;
    }

    /**
     * Opens a stream and waits for its retry line. The server registers the connection (and runs the replay) before
     * the response starts, so once the retry line arrived the stream receives every later message.
     */
    protected SseClient openStream(UUID userId, String... roles) {
        return open(stream(token(userId, roles)));
    }

    protected SseClient open(SseClient client) {
        assertThat(client.status()).isEqualTo(200);
        assertThat(client.await(block -> block.retry() != null, WAIT)).as("retry line").isNotNull();
        return client;
    }

    protected static void waitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            RealtimeRecorder.sleep(20);
        }
    }

    protected JsonNode data(SseClient.Block block) {
        return json.readTree(block.data());
    }
}
