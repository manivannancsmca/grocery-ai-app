package com.grocery.vendor;

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
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Real MySQL 8.4 in a throw-away container. Requests are authenticated with Spring Security's test
 * JWT support (same claims identity issues), so the real security chain and @PreAuthorize rules run.
 * Avro event payloads (ADR-0011) use grocery-common's in-memory Schema Registry, which registers the schemas.
 * The clock is a {@link MutableClock}: the real time unless a test pins it (reset after every test). The scheduled
 * auto-reject poller is off: tests run pollers themselves, with a clock of their choice.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "grocery.events.avro.schema-registry-url=mock://vendor-service",
        "grocery.events.avro.auto-register-schemas=true",
        "grocery.fulfillment.auto-reject-enabled=false",
        // the race tests queue more transactions than the pool has connections for a moment
        "spring.datasource.hikari.connection-timeout=30000"
})
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
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
    protected MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

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
}
