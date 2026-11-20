package com.grocery.ai.gateway;

import com.grocery.common.correlation.CorrelationId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * What every call through the gateway carries: the caller's own access token (the AI has exactly the user's rights;
 * each service validates the token again) and the request's correlation ID. Captured on the request thread, because
 * chat turns continue on other threads.
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
}
