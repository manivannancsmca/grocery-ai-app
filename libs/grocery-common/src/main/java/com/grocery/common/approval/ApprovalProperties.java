package com.grocery.common.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code grocery.approvals.*} — verification of {@code X-Approval-Token} in a target service (grocery-common 0.8.0,
 * contract Phase 11 §5). The retention of {@code consumed_approval} rows,
 * {@code grocery.approvals.consumed-retention} (30d), belongs to the housekeeping settings.
 */
@ConfigurationProperties(prefix = "grocery.approvals")
public class ApprovalProperties {

    /** Default JWKS location when neither {@code grocery.approvals.jwks-uri} nor {@code APPROVAL_JWKS_URI} is set. */
    public static final String DEFAULT_JWKS_URI = "http://localhost:8115/.well-known/approval-jwks.json";

    /** Verify approval tokens (order, payment, vendor and restaurant services). Off by default. */
    private boolean enabled = false;

    /**
     * The orchestrator's approval JWKS. When unset: the {@code APPROVAL_JWKS_URI} environment variable, otherwise
     * {@value #DEFAULT_JWKS_URI}.
     */
    private String jwksUri;

    /** Expected {@code iss}. */
    private String issuer = ApprovalToken.DEFAULT_ISSUER;

    /** Expected {@code aud}; defaults to {@code spring.application.name}. */
    private String audience;

    /** Tolerated clock difference for {@code exp} and {@code iat}. */
    private Duration clockSkew = Duration.ofSeconds(5);

    /** How long a fetched JWKS is used before it is fetched again. */
    private Duration jwksCacheTtl = Duration.ofMinutes(5);

    /**
     * Minimum time between JWKS fetches caused by unknown key ids (a rotation is picked up at once; a flood of unknown
     * ids is not forwarded to the orchestrator). {@code 0} disables the limit. Must be shorter than the cache TTL.
     */
    private Duration jwksMinRefetchInterval = Duration.ofSeconds(30);

    /** Connect and read timeout of a JWKS fetch. */
    private Duration jwksTimeout = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public Duration getJwksCacheTtl() {
        return jwksCacheTtl;
    }

    public void setJwksCacheTtl(Duration jwksCacheTtl) {
        this.jwksCacheTtl = jwksCacheTtl;
    }

    public Duration getJwksMinRefetchInterval() {
        return jwksMinRefetchInterval;
    }

    public void setJwksMinRefetchInterval(Duration jwksMinRefetchInterval) {
        this.jwksMinRefetchInterval = jwksMinRefetchInterval;
    }

    public Duration getJwksTimeout() {
        return jwksTimeout;
    }

    public void setJwksTimeout(Duration jwksTimeout) {
        this.jwksTimeout = jwksTimeout;
    }
}
