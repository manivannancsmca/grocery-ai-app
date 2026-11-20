package com.grocery.common.autoconfigure;

import com.grocery.common.security.CurrentUserArgumentResolver;
import com.grocery.common.security.GrocerySecurityProperties;
import com.grocery.common.security.ProblemDetailSecurityHandlers;
import com.grocery.common.security.SecurityExceptionAdvice;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Zero-trust resource server for every servlet service: each service validates the JWT itself
 * (signature via the identity-service JWKS, issuer, audience, expiry) — it never trusts the
 * gateway blindly. Stateless, CSRF off (Bearer tokens, no cookies), roles claim → ROLE_*
 * authorities, {@code @PreAuthorize} enabled, 401/403 as ProblemDetail.
 *
 * <p>Runs before Spring Boot's own security auto-configurations so their default chains back off.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, JwtAuthenticationConverter.class})
@ConditionalOnProperty(prefix = "grocery.security", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(GrocerySecurityProperties.class)
@EnableWebSecurity
@EnableMethodSecurity
public class GrocerySecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProblemDetailSecurityHandlers problemDetailSecurityHandlers(ObjectProvider<JsonMapper> jsonMapper) {
        return new ProblemDetailSecurityHandlers(jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter groceryJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain grocerySecurityFilterChain(HttpSecurity http, GrocerySecurityProperties properties,
                                                   JwtAuthenticationConverter converter,
                                                   ProblemDetailSecurityHandlers handlers) throws Exception {
        List<String> publicPaths = properties.getPublicPaths();
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // /actuator/prometheus (0.9.0): scraped on the service's own port; the gateway never routes /actuator/**
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                            "/actuator/prometheus").permitAll();
                    if (!publicPaths.isEmpty()) {
                        auth.requestMatchers(publicPaths.toArray(String[]::new)).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityExceptionAdvice securityExceptionAdvice() {
        return new SecurityExceptionAdvice();
    }

    @Bean
    WebMvcConfigurer currentUserArgumentResolverConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentUserArgumentResolver());
            }
        };
    }
}
