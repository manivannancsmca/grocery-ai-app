package com.grocery.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailSecurityHandlersTest {

    private final ProblemDetailSecurityHandlers handlers = new ProblemDetailSecurityHandlers(JsonMapper.builder().build());

    @Test
    void missingTokenIsUnauthenticated() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.commence(new MockHttpServletRequest(), response, new InsufficientAuthenticationException("x"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"UNAUTHENTICATED\"");
    }

    @Test
    void expiredTokenIsReportedSoTheClientRefreshes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.commence(request, response, new InvalidBearerTokenException("Jwt expired at 2026-11-11T10:00:00Z"));

        assertThat(response.getContentAsString()).contains("\"errorCode\":\"TOKEN_EXPIRED\"");
    }

    @Test
    void accessDeniedIsForbidden() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.handle(new MockHttpServletRequest(), response, new AccessDeniedException("no"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"FORBIDDEN\"");
    }
}
