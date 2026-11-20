package com.grocery.reporting;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.reporting.audit.AuditService;
import com.grocery.reporting.event.AuditListener;
import com.grocery.reporting.event.FactsListener;
import com.grocery.reporting.facts.FactsService.Outcome;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One Spring context for every integration test: real MySQL 8.4 and a real Kafka broker in throw-away containers, the
 * Avro deserializer against Confluent's in-memory Schema Registry. The Kafka listener runs ({@link KafkaEndToEndIntegrationTest}
 * publishes to it); the other tests hand records to the listener directly, as the container would — after encoding
 * them with the real schema and decoding them with this service's own value deserializer ({@link #dispatch}), so
 * every test event is exactly what a producer can publish. Report calls go through MockMvc with Spring Security's
 * test JWT (the real security chain and {@code @PreAuthorize} run). Every test starts from empty fact tables.
 */
@SpringBootTest(properties = {
        // ADR-0011: consumers decode Avro against the in-memory Schema Registry
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true",
        // the audit trail's daily purge runs only when a test calls it
        "grocery.reporting.audit.purge-cron=-"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    /** Confluent's in-memory Schema Registry, one scope per service (shared by every client of this JVM). */
    public static final String SCHEMA_REGISTRY_URL = "mock://reporting-service";
    public static final List<String> TOPICS =
            List.of("order.events", "payment.events", "delivery.events", "review.events", "vendor.events",
                    // the audit trail also reads these
                    "identity.events", "restaurant.events", "pricing.events", "inventory.events", "ai.events");
    static final List<String> FACT_TABLES = List.of("order_fact", "order_line_fact", "refund_fact", "return_fact",
            "return_line_fact", "delivery_fact", "review_fact", "rating_fact", "receipt_fact", "receipt_line_fact",
            "store_dim", "item_dim", "partner_dim", "processed_event", "audit_entry", "audit_actor");

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");

    static {
        Startables.deepStart(MYSQL, KAFKA).join();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(Stream.concat(TOPICS.stream(), TOPICS.stream().map(t -> t + ".DLT"))
                    .map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create the test topics", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected FactsListener listener;
    @Autowired
    protected AuditListener auditListener;
    @Autowired
    protected AvroEventEncoder avroEncoder;
    @Autowired
    protected JsonAvroConverter avroConverter;
    @Autowired
    protected ConsumerFactory<?, ?> consumerFactory;

    protected JdbcClient jdbc;

    @BeforeEach
    void emptyFacts() {
        jdbc = JdbcClient.create(dataSource);
        FACT_TABLES.forEach(table -> jdbc.sql("TRUNCATE TABLE " + table).update());
    }

    // ---------------------------------------------------------------- events

    /** The record encoded with its real schema (Avro, strict), decoded by this service, handed to the listener. */
    protected Outcome dispatch(ConsumerRecord<String, String> record) {
        return listener.handle(viaAvro(record));
    }

    @SafeVarargs
    protected final void dispatchAll(ConsumerRecord<String, String>... records) {
        for (ConsumerRecord<String, String> record : records) {
            assertThat(dispatch(record)).as(EventEnvelope.header(record, "eventType")).isEqualTo(Outcome.APPLIED);
        }
    }

    /** The record through the Avro round trip, handed to the audit trail's listener. */
    protected AuditService.Outcome audit(ConsumerRecord<String, String> record) {
        return auditListener.handle(viaAvro(record));
    }

    /** The JSON as is, without the Avro round trip (fields a schema does not have, missing ids). */
    protected Outcome dispatchRaw(ConsumerRecord<String, String> record) {
        return listener.handle(record);
    }

    /** The Kafka value bytes (Confluent wire format) of the record, as Debezium publishes them. */
    protected byte[] wire(ConsumerRecord<String, String> record) {
        byte[] wire = avroEncoder.encode(record.topic(), EventEnvelope.header(record, "eventType"), 1,
                avroConverter.readExact(record.value()));
        assertThat(AvroEventDeserializer.isAvro(wire)).isTrue();
        return wire;
    }

    private ConsumerRecord<String, String> viaAvro(ConsumerRecord<String, String> record) {
        byte[] wire = wire(record);
        Deserializer<?> values = consumerFactory.getValueDeserializer();
        Headers headers = new RecordHeaders();
        Object value = values.deserialize(record.topic(), headers, wire);
        assertThat(headers.lastHeader("springDeserializerExceptionValue")).as("deserialization failure").isNull();
        assertThat(value).isInstanceOf(String.class);
        ConsumerRecord<String, String> decoded = new ConsumerRecord<>(record.topic(), 0, 0L, record.key(),
                (String) value);
        record.headers().forEach(header -> decoded.headers().add(header));
        return decoded;
    }

    protected boolean processed(ConsumerRecord<String, String> record) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE event_id = :id AND consumer = :consumer")
                .param("id", TestEvents.eventId(record))
                .param("consumer", "reporting-service.facts")
                .query(Long.class).single() == 1;
    }

    // ---------------------------------------------------------------- reports (MockMvc)

    /** A request authenticated as a new user holding {@code roles}. */
    protected static JwtRequestPostProcessor as(String... roles) {
        List<String> roleList = Arrays.asList(roles);
        UUID userId = UUID.randomUUID();
        return jwt()
                .jwt(token -> token.subject(userId.toString()).claim("roles", roleList))
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    protected static JwtRequestPostProcessor admin() {
        return as("FINANCE_ADMIN");
    }

    /** {@code GET path?k=v&…} as an admin → 200 → the JSON body. */
    protected JsonNode report(String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(path).with(admin());
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return EXACT.readTree(body);
    }

    /** Reads JSON numbers as written ({@code 741.00} stays {@code 741.00}), so tests can check the 2 decimals. */
    protected static final JsonMapper EXACT = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .build();
}
