package com.grocery.customer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * Avro event payloads (ADR-0011) use grocery-common's in-memory Schema Registry.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "grocery.events.avro.schema-registry-url=mock://customer-service",
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

    /** A request authenticated as {@code userId} holding {@code roles}. */
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

    /** The {@code email} claim {@link #as} puts in the token. */
    protected static String emailOf(UUID userId) {
        return "user-" + userId.toString().substring(0, 8) + "@example.com";
    }

    /** The {@code name} claim {@link #as} puts in the token. */
    protected static String nameOf(UUID userId) {
        return "User " + userId.toString().substring(0, 8);
    }
}
