package com.grocery.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code grocery.security.*} — the shared resource-server setup. Token validation itself is
 * configured with Spring Boot's standard properties:
 * <pre>
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://identity-service:8101/.well-known/jwks.json
 * spring.security.oauth2.resourceserver.jwt.issuer-uri:  https://identity.grocery.local
 * spring.security.oauth2.resourceserver.jwt.audiences:   grocery-api
 * </pre>
 */
@ConfigurationProperties(prefix = "grocery.security")
public class GrocerySecurityProperties {

    /** Register the shared stateless JWT SecurityFilterChain. */
    private boolean enabled = true;

    /** Extra unauthenticated paths (actuator health/info and, since 0.9.0, prometheus are always public). */
    private List<String> publicPaths = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}
