package com.grocery.identity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real MySQL 8.4 and Redis in throw-away containers, shared by all integration test classes.
 * Kafka listeners are switched off; event handling is tested by calling the handler directly.
 * Avro event payloads (ADR-0011) use grocery-common's in-memory Schema Registry, which registers the schemas.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "grocery.events.avro.schema-registry-url=mock://identity-service",
        "grocery.events.avro.auto-register-schemas=true",
        "grocery.identity.bootstrap-admin.email=" + AbstractIntegrationTest.ADMIN_EMAIL,
        "grocery.identity.bootstrap-admin.password=" + AbstractIntegrationTest.ADMIN_PASSWORD
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final String ADMIN_EMAIL = "admin@test.local";
    static final String ADMIN_PASSWORD = "Admin@12345";
    static final String PASSWORD = "Secret123";

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    protected MockHttpServletResponse register(String email, String role) throws Exception {
        String body = """
                {"email":"%s","password":"%s","fullName":"Test %s","phone":"+919876543210","role":"%s"}
                """.formatted(email, PASSWORD, role, role);
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    protected MockHttpServletResponse login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse();
    }

    protected JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    protected String accessToken(MockHttpServletResponse response) throws Exception {
        return body(response).get("accessToken").asString();
    }

    /** Value of the grocery_rt refresh cookie from a Set-Cookie header. */
    protected static String refreshCookie(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        String value = header.substring("grocery_rt=".length(), header.indexOf(';'));
        return value;
    }

    /** Decoded JWT claims (signature is verified by the services, not needed here). */
    protected JsonNode claims(String jwt) {
        String payload = jwt.split("\\.")[1];
        return json.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }
}
