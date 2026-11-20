package com.grocery.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * Edge security. The gateway authenticates (valid, unexpired token from our issuer for our audience)
 * and applies one coarse rule: /api/admin/** needs an admin role. Fine-grained authorisation and
 * ownership checks stay in each service, which validates the same token again (zero trust).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Reachable without a token. Everything else under /api requires one. The {@code /actuator/*} entries apply to the
     * internal management port only (management.server.port, 9080): the gateway's health probes and its own metrics,
     * scraped by Prometheus / the ADOT sidecar. The public port serves no actuator, and no route forwards
     * {@code /actuator/**} to a service.
     */
    static final String[] PUBLIC_PATHS = {
            "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
            // forgot / reset password: the caller cannot sign in (POST /api/auth/password/change needs a token)
            "/api/auth/password/forgot", "/api/auth/password/reset",
            "/.well-known/jwks.json", "/api/health/**", "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/actuator/prometheus"
    };

    /** Razorpay posts webhooks without a token; payment-service checks the webhook signature (Phase 6). POST only. */
    static final String PAYMENT_WEBHOOK = "/api/payments/razorpay/webhook";

    /**
     * Catalog browsing is open to guests (storefront, search, restaurant pages) — reads only. Phase 12: the published
     * reviews of a product, dish, store or partner and their rating summary too (exactly these two paths: the
     * customer's own {@code /api/reviews/mine} and {@code /api/reviews/eligible} still need a token). The store
     * directory ({@code /api/stores/**}, vendor-service: the grocery stores delivering to a point, one store's public
     * card) is a guest read as well.
     */
    static final String[] PUBLIC_READ_PATHS = {"/api/search/**", "/api/catalog/**", "/api/reviews", "/api/reviews/summary",
            "/api/stores/**"};

    static final String[] ADMIN_ROLES = {"SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER"};

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemDetailResponses problems,
                                                  CorsConfigurationSource corsConfigurationSource) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)          // Bearer tokens; the cookie endpoint has its own CSRF guard
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .pathMatchers(HttpMethod.GET, PUBLIC_READ_PATHS).permitAll()
                        .pathMatchers(HttpMethod.POST, PAYMENT_WEBHOOK).permitAll()
                        // Kafka Connect has no authentication: only platform operators may restart connectors
                        .pathMatchers(HttpMethod.POST, "/api/admin/system/connectors/**").hasAnyRole("SUPER_ADMIN", "OPS_ADMIN")
                        .pathMatchers("/api/admin/**").hasAnyRole(ADMIN_ROLES)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter)))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }

    /**
     * CORS lives in the security chain (not gateway globalcors) so that even 401/403 responses carry
     * CORS headers and the browser can read the error. Only the configured frontend origins.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${grocery.gateway.allowed-origins:http://localhost:6420}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id", "X-Requested-With",
                "Idempotency-Key", "Last-Event-ID"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After", "Idempotent-Replayed"));
        config.setAllowCredentials(true);   // refresh-token cookie
        config.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
