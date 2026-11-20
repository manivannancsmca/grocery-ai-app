package com.grocery.order.client;

import com.grocery.common.correlation.CorrelationId;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * What every downstream call made for a customer carries: the customer's own access token (zero trust — each
 * service validates it again, and "me" in cart-service is the token's subject) and the request's correlation ID.
 *
 * @param authorization {@code Bearer <token>}, or null without an authenticated JWT
 * @param correlationId the request's {@code X-Correlation-Id}, or null
 */
public record CallContext(String authorization, String correlationId) {

    /** The current request's JWT (from the security context) and correlation ID. */
    public static CallContext current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String authorization = null;
        if (authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> token) {
            authorization = "Bearer " + token.getToken().getTokenValue();
        }
        return new CallContext(authorization, CorrelationId.current());
    }

    void applyTo(HttpHeaders headers) {
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (correlationId != null) {
            headers.set(CorrelationId.HEADER, correlationId);
        }
    }
}
