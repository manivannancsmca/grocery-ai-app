package com.grocery.catalog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MySQL 8.4 in a throw-away container. Requests are authenticated with Spring Security's test
 * JWT support (same claims identity issues), so the real security chain and @PreAuthorize rules run.
 * The database is shared by all test classes: tests use random names and ids, never fixed ones.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // ADR-0011: the in-memory Schema Registry; tests register the central schemas themselves
        "grocery.events.avro.schema-registry-url=mock://catalog-command-service",
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected DataSource dataSource;

    protected final UUID admin = UUID.randomUUID();

    /** A request authenticated as {@code userId} holding {@code roles}. */
    protected static JwtRequestPostProcessor as(UUID userId, String... roles) {
        return as(userId, null, roles);
    }

    /** Same, with the {@code vendor_id} claim identity adds while the user's vendor is APPROVED. */
    protected static JwtRequestPostProcessor as(UUID userId, UUID vendorId, String... roles) {
        List<String> roleList = Arrays.asList(roles);
        return jwt()
                .jwt(token -> {
                    token.subject(userId.toString())
                            .claim("email", "user-" + userId.toString().substring(0, 8) + "@example.com")
                            .claim("name", "User " + userId.toString().substring(0, 8))
                            .claim("roles", roleList);
                    if (vendorId != null) {
                        token.claim("vendor_id", vendorId.toString());
                    }
                })
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    /** An approved vendor's owner (user id derived from the vendor id for brevity). */
    protected static JwtRequestPostProcessor vendor(UUID vendorId) {
        return as(UUID.nameUUIDFromBytes(vendorId.toString().getBytes()), vendorId, "VENDOR");
    }

    protected JwtRequestPostProcessor opsAdmin() {
        return as(admin, "OPS_ADMIN");
    }

    protected JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Creates a category through the API and returns its id. */
    protected String createCategory(String name, String parentId) throws Exception {
        String body = parentId == null
                ? "{\"name\":\"%s\"}".formatted(name)
                : "{\"name\":\"%s\",\"parentId\":\"%s\"}".formatted(name, parentId);
        return body(mvc.perform(post("/api/admin/categories").with(opsAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())).get("id").asString();
    }

    protected static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Payloads of an aggregate's outbox events of one type, oldest first. */
    protected List<JsonNode> events(String aggregateId, String eventType) {
        return JdbcClient.create(dataSource)
                .sql("SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ? ORDER BY occurred_at, id")
                .param(aggregateId).param(eventType)
                .query(String.class).list().stream()
                .map(json::readTree)
                .toList();
    }

    protected List<JsonNode> productEvents(String productId) {
        return events(productId, "ProductUpserted");
    }

    protected JsonNode lastProductEvent(String productId) {
        return productEvents(productId).getLast();
    }

    protected List<JsonNode> categoryEvents(String categoryId) {
        return events(categoryId, "CategoryChanged");
    }
}
