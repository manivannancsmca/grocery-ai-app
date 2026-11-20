package com.grocery.catalogquery;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import com.grocery.catalogquery.event.CatalogEventsListener;
import com.grocery.catalogquery.event.InventoryEventsListener;
import com.grocery.catalogquery.event.PricingEventsListener;
import com.grocery.catalogquery.event.RestaurantEventsListener;
import com.grocery.catalogquery.event.ReviewEventsListener;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.IndexManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Real Elasticsearch 9.4.6 in a throw-away container (started once for all test classes). Events are fed by
 * calling the Kafka listener methods directly with records carrying the Debezium headers; Kafka itself is not
 * started. Every test starts with empty indexes.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // ADR-0011: the in-memory Schema Registry; tests register the central schemas themselves
        "grocery.events.avro.schema-registry-url=mock://catalog-query-service",
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>("elasticsearch:9.4.6")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        ELASTICSEARCH.start();
    }

    @DynamicPropertySource
    static void elasticsearch(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected ElasticsearchClient elasticsearch;
    @Autowired
    protected IndexManager indexManager;
    @Autowired
    protected CatalogEventsListener catalogEvents;
    @Autowired
    protected RestaurantEventsListener restaurantEvents;
    @Autowired
    protected PricingEventsListener pricingEvents;
    @Autowired
    protected InventoryEventsListener inventoryEvents;
    @Autowired
    protected ReviewEventsListener reviewEvents;
    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void emptyIndexes() throws IOException {
        for (IndexDefinition definition : IndexDefinition.values()) {
            indexManager.recheck(definition);
            elasticsearch.deleteByQuery(d -> d.index(definition.alias())
                    .query(q -> q.matchAll(m -> m))
                    .conflicts(Conflicts.Proceed)
                    .refresh(true));
        }
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** Makes everything indexed so far searchable. */
    protected void refresh() throws IOException {
        elasticsearch.indices().refresh(r -> r.index("products", "restaurants", "categories")
                .ignoreUnavailable(true).allowNoIndices(true));
    }

    protected void publishCatalogEvent(String eventType, String key, Map<String, Object> payload) {
        catalogEvents.on(record(CatalogEventsListener.TOPIC, key, eventType, payload));
    }

    protected void publishRestaurantEvent(String eventType, String key, Map<String, Object> payload) {
        restaurantEvents.on(record(RestaurantEventsListener.TOPIC, key, eventType, payload));
    }

    protected void publishPricingEvent(String eventType, String key, Map<String, Object> payload) {
        pricingEvents.on(record(PricingEventsListener.TOPIC, key, eventType, payload));
    }

    protected void publishInventoryEvent(String eventType, String key, Map<String, Object> payload) {
        inventoryEvents.on(record(InventoryEventsListener.TOPIC, key, eventType, payload));
    }

    protected void publishReviewEvent(String eventType, String key, Map<String, Object> payload) {
        reviewEvents.on(record(ReviewEventsListener.TOPIC, key, eventType, payload));
    }

    protected ConsumerRecord<String, String> record(String topic, String key, String eventType,
                                                    Map<String, Object> payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, key,
                json.writeValueAsString(payload));
        record.headers().add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

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

    protected static String id() {
        return UUID.randomUUID().toString();
    }
}
