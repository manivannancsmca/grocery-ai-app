package com.grocery.review.config;

import com.grocery.common.security.ProblemDetailSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * grocery-common's zero-trust resource-server chain (stateless, JWT validated here, roles → {@code ROLE_*},
 * {@code @PreAuthorize}, 401/403 as ProblemDetail), with one difference: the public reads are public for
 * <b>GET only</b>. {@code grocery.security.public-paths} cannot say that, and {@code /api/reviews} is also
 * {@code POST /api/reviews} (customers), which must answer 401 without a token rather than reach the controller
 * anonymously. Declaring this chain makes grocery-common's chain back off; its converter and handlers are reused.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewSecurityConfig {

    /** {@code GET /api/reviews} (published reviews of a target) and {@code GET /api/reviews/summary}. */
    static final String[] PUBLIC_READS = {"/api/reviews", "/api/reviews/summary"};

    @Bean
    SecurityFilterChain reviewSecurityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                                                  ProblemDetailSecurityHandlers handlers) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }
}
