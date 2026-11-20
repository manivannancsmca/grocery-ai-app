package com.grocery.gateway.filter;

import com.grocery.gateway.security.ProblemDetailResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Turns "the service is not there" into a clear, retryable answer instead of a generic 500:
 * connection refused / unknown host → 503 SERVICE_UNAVAILABLE, response timeout → 504 GATEWAY_TIMEOUT,
 * both as ProblemDetail. The React app shows "temporarily unavailable" and the admin System Status
 * page shows the service as DOWN.
 */
@Component
public class DownstreamErrorGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DownstreamErrorGlobalFilter.class);

    private final ProblemDetailResponses problems;

    public DownstreamErrorGlobalFilter(ProblemDetailResponses problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(error -> {
            HttpStatus status = classify(error);
            if (status == null || exchange.getResponse().isCommitted()) {
                return Mono.error(error);
            }
            log.warn("Downstream failure for {} {}: {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), error.toString());
            return status == HttpStatus.GATEWAY_TIMEOUT
                    ? problems.write(exchange, status, "GATEWAY_TIMEOUT", "The service took too long to respond. Please try again.")
                    : problems.write(exchange, status, "SERVICE_UNAVAILABLE", "The service is temporarily unavailable. Please try again.");
        });
    }

    static HttpStatus classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t.getClass().getName().endsWith("ConnectTimeoutException")) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            if (t instanceof TimeoutException || t.getClass().getName().endsWith(".TimeoutException")) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
