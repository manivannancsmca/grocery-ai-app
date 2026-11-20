package com.grocery.gateway.security;

import com.grocery.gateway.filter.CorrelationIdWebFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 401/403 from the gateway in the same ProblemDetail shape the services use, so the React client
 * handles every error the same way (e.g. refresh on TOKEN_EXPIRED / TOKEN_REVOKED).
 */
@Component
public class ProblemDetailResponses implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        String errorCode;
        if (authorization == null || authorization.isBlank()) {
            errorCode = "UNAUTHENTICATED";
        } else {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            errorCode = message.contains("expired") ? "TOKEN_EXPIRED" : "INVALID_TOKEN";
        }
        return write(exchange, HttpStatus.UNAUTHORIZED, errorCode, switch (errorCode) {
            case "TOKEN_EXPIRED" -> "The access token has expired.";
            case "INVALID_TOKEN" -> "The access token is invalid.";
            default -> "Authentication is required.";
        });
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to perform this operation.");
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String errorCode, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        // the exchange's id (CorrelationIdWebFilter runs before the security chain); only safe characters, so it can
        // be written into the JSON as is
        String correlationId = CorrelationIdWebFilter.correlationId(exchange);
        String body = "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"errorCode\":\"%s\"%s}"
                .formatted(status.getReasonPhrase(), status.value(), detail, errorCode,
                        correlationId == null ? "" : ",\"correlationId\":\"" + correlationId + "\"");
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (correlationId != null) {
            response.getHeaders().set(CorrelationIdWebFilter.HEADER, correlationId);
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            response.getHeaders().set("WWW-Authenticate", "Bearer");
        }
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
