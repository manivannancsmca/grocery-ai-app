package com.grocery.common.autoconfigure;

import com.grocery.common.approval.ApprovalProperties;
import com.grocery.common.approval.ApprovalTokenFilter;
import com.grocery.common.approval.ApprovalTokenVerifier;
import com.grocery.common.approval.ConsumedApprovals;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Clock;

/**
 * Approval-token verification in a target service (grocery-common 0.8.0, contract Phase 11 §5): the
 * {@link ApprovalTokenFilter}, registered as a servlet filter right after the Spring Security filter chain
 * ({@code spring.security.filter.order} + 10), so it sees the authenticated user and runs before every handler.
 *
 * <p>Only with {@code grocery.approvals.enabled=true} (off by default) in a servlet application with Spring Security's
 * JWT support. It needs the service's {@code DataSource} (the {@code consumed_approval} table): enabling it without
 * one fails the start rather than silently accepting approval tokens unverified. Every bean backs off if the service
 * declares its own.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.security.core.context.SecurityContextHolder",
        "org.springframework.security.oauth2.jwt.Jwt",
        "com.nimbusds.jose.jwk.source.JWKSourceBuilder",
        "tools.jackson.databind.json.JsonMapper"})
@ConditionalOnProperty(prefix = "grocery.approvals", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ApprovalProperties.class)
public class GroceryApprovalAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GroceryApprovalAutoConfiguration.class);

    /** Spring Boot's default {@code spring.security.filter.order}. */
    static final int DEFAULT_SECURITY_FILTER_ORDER = -100;

    @Bean
    @ConditionalOnMissingBean
    ApprovalTokenVerifier groceryApprovalTokenVerifier(ApprovalProperties properties, Environment environment) {
        String audience = properties.getAudience() != null && !properties.getAudience().isBlank()
                ? properties.getAudience() : environment.getProperty("spring.application.name");
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("grocery.approvals.enabled=true needs the approval token audience: set "
                    + "spring.application.name (or grocery.approvals.audience)");
        }
        URL url = jwksUri(properties, environment);
        log.info("Approval tokens verified for audience {} (issuer {}) with keys from {}", audience,
                properties.getIssuer(), url);
        return new ApprovalTokenVerifier(
                ApprovalTokenVerifier.remoteKeys(url, properties.getJwksTimeout(), properties.getJwksCacheTtl(),
                        properties.getJwksMinRefetchInterval()),
                properties.getIssuer(), audience, properties.getClockSkew(), Clock.systemUTC());
    }

    /** {@code grocery.approvals.jwks-uri}, else {@code APPROVAL_JWKS_URI}, else the local default. */
    static URL jwksUri(ApprovalProperties properties, Environment environment) {
        String jwksUri = properties.getJwksUri() != null && !properties.getJwksUri().isBlank()
                ? properties.getJwksUri()
                : environment.getProperty("APPROVAL_JWKS_URI", ApprovalProperties.DEFAULT_JWKS_URI);
        try {
            return URI.create(jwksUri.strip()).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("grocery.approvals.jwks-uri is not a URL: " + jwksUri, e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    ConsumedApprovals groceryConsumedApprovals(ObjectProvider<DataSource> dataSource) {
        DataSource unique = dataSource.getIfUnique();
        if (unique == null) {
            throw new IllegalStateException("grocery.approvals.enabled=true needs a single DataSource with the "
                    + "consumed_approval table (see the grocery-common README)");
        }
        return new ConsumedApprovals(unique, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    ApprovalTokenFilter groceryApprovalTokenFilter(ApprovalTokenVerifier verifier, ConsumedApprovals consumedApprovals,
                                                   ObjectProvider<JsonMapper> jsonMapper) {
        return new ApprovalTokenFilter(verifier, consumedApprovals,
                jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }

    /** Right after Spring Security's filter chain (whatever {@code spring.security.filter.order} says). */
    @Bean
    @ConditionalOnMissingBean(name = "groceryApprovalTokenFilterRegistration")
    FilterRegistrationBean<ApprovalTokenFilter> groceryApprovalTokenFilterRegistration(ApprovalTokenFilter filter,
                                                                                       Environment environment) {
        FilterRegistrationBean<ApprovalTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("groceryApprovalTokenFilter");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(environment.getProperty("spring.security.filter.order", Integer.class,
                DEFAULT_SECURITY_FILTER_ORDER) + 10);
        return registration;
    }
}
