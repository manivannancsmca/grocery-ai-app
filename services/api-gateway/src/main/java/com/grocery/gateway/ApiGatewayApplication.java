package com.grocery.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single entry point for the React app.
 *
 * <p>Phase 1: routing, CORS, correlation IDs. Phase 2 adds JWT validation, the suspended-user
 * denylist (Redis), rate limiting and stripping of client-supplied identity headers.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
