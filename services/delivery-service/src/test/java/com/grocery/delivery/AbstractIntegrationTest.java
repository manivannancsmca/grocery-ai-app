package com.grocery.delivery;

import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Real MySQL 8.4 and Redis 8 in throw-away containers. Requests are authenticated with Spring Security's test
 * JWT support (same claims identity issues), so the real security chain and @PreAuthorize rules run.
 * Avro event payloads (ADR-0011) use grocery-common's in-memory Schema Registry, which registers the schemas.
 * The clock is a {@link MutableClock}: the real time unless a test pins it (reset after every test). The scheduled
 * dispatch poller is off: tests run it themselves, with a clock of their choice. No Kafka broker: order.events records
 * are handed to the listener directly, and the {@code delivery.location} producer is a {@link RecordingLocationSender}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "grocery.events.avro.schema-registry-url=mock://delivery-service",
        "grocery.events.avro.auto-register-schemas=true",
        "grocery.delivery.pollers-enabled=false",
        // the race tests queue more transactions than the pool has connections for a moment
        "spring.datasource.hikari.connection-timeout=30000"
})
@AutoConfigureMockMvc
@Import(TestDispatchConfiguration.class)
public abstract class AbstractIntegrationTest {

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
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected RecordingLocationSender locationSender;

    @AfterEach
    void resetClockAndProducer() {
        clock.reset();
        locationSender.reset();
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
}
