package com.grocery.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Forwards the exchange's {@code X-Correlation-Id} to the downstream service. The id itself is chosen (kept or
 * created) by {@link CorrelationIdWebFilter}, which runs for every exchange — routed or not — and also echoes it on the
 * response. Should that filter not have run, this one falls back to the same rules and echoes the id itself.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER = CorrelationIdWebFilter.HEADER;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean chosen = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE) != null;
        String correlationId = CorrelationIdWebFilter.correlationId(exchange);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        String value = correlationId;
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, value))
                .build();
        if (!chosen) {
            // Set just before the response is committed: downstream services echo the header too,
            // and set() replaces their copy so the client receives exactly one value.
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(HEADER, value);
                return Mono.empty();
            });
        }
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static String sanitize(String candidate) {
        return CorrelationIdWebFilter.sanitize(candidate);
    }
}
