package com.grocery.common.security;

import com.grocery.common.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 401/403 responses from the security filter chain, in the same ProblemDetail shape as the
 * rest of the API, so the React client handles every error the same way.
 */
public class ProblemDetailSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public ProblemDetailSecurityHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String errorCode = authErrorCode(request, authException);
        String detail = switch (errorCode) {
            case "TOKEN_EXPIRED" -> "The access token has expired.";
            case "INVALID_TOKEN" -> "The access token is invalid.";
            default -> "Authentication is required.";
        };
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, detail, errorCode);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "You are not allowed to perform this operation.", "FORBIDDEN");
    }

    static String authErrorCode(HttpServletRequest request, AuthenticationException ex) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return "UNAUTHENTICATED";
        }
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("expired") ? "TOKEN_EXPIRED" : "INVALID_TOKEN";
    }

    private void write(HttpServletResponse response, HttpStatus status, String detail, String errorCode)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("errorCode", errorCode);
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }
}
