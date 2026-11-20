package com.grocery.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Identity and authentication service.
 *
 * <p>Phase 1: skeleton only (database, Flyway, outbox relay, health).
 * Phase 2 adds registration, login, JWT/JWKS and refresh-token rotation.
 */
@SpringBootApplication
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
