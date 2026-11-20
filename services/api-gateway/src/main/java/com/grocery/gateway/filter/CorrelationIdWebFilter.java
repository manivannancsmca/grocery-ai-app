package com.grocery.gateway.filter;

import io.micrometer.common.KeyValue;
import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The {@code X-Correlation-Id} of <b>every</b> exchange (Phase 2 §1: every response echoes it), including the answers
 * the gateway writes itself — Spring Security's 401/403 ProblemDetails, rate-limit and downstream-error answers,
 * {@code /api/health/gateway}, the actuator. A {@link WebFilter} ordered before the security filter chain (order
 * -100), so it runs for routed and non-routed requests alike:
 * <ul>
 *   <li>keeps a safe incoming value (8–64 of {@code [A-Za-z0-9._-]}, as {@code com.grocery.common.correlation
 *       .CorrelationId} in the services) or creates one;</li>
 *   <li>puts it on the request (so {@link CorrelationIdGlobalFilter} forwards exactly this value to the service), on
 *       the exchange attributes ({@link #ATTRIBUTE}) and on the response just before it is committed — {@code set},
 *       so a service's echo never makes it appear twice;</li>
 *   <li>feeds the logs ({@code correlationId} in the MDC through Reactor's context propagation,
 *       {@code spring.reactor.context-propagation=auto}) and the trace (span attribute {@code correlation.id} of the
 *       server observation).</li>
 * </ul>
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    /** Exchange attribute holding the exchange's correlation id. */
    public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".correlationId";
    /** The MDC key of the services' ECS logs ({@code com.grocery.common.correlation.CorrelationId.MDC_KEY}). */
    public static final String MDC_KEY = "correlationId";
    /** High-cardinality key of the server observation: a span attribute, never a metric tag. */
    public static final String SPAN_ATTRIBUTE = "correlation.id";
    /** Before Spring Security's {@code WebFilterChainProxy} (-100) and every other web filter. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    static {
        // Reactor's automatic context propagation restores the MDC entry from the subscriber context in operators
        ContextRegistry.getInstance().registerThreadLocalAccessor(MDC_KEY,
                () -> MDC.get(MDC_KEY), value -> MDC.put(MDC_KEY, value), () -> MDC.remove(MDC_KEY));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = sanitize(exchange.getRequest().getHeaders().getFirst(HEADER));
        String correlationId = incoming != null ? incoming : UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTRIBUTE, correlationId);
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            response.getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });
        ServerRequestObservationContext.findCurrent(exchange.getAttributes()).ifPresent(context ->
                context.addHighCardinalityKeyValue(KeyValue.of(SPAN_ATTRIBUTE, correlationId)));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, correlationId))
                .build();
        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(Context.of(MDC_KEY, correlationId));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** The exchange's correlation id: the attribute set by this filter, else a safe request header, else null. */
    public static String correlationId(ServerWebExchange exchange) {
        Object attribute = exchange.getAttribute(ATTRIBUTE);
        if (attribute instanceof String value) {
            return value;
        }
        return sanitize(exchange.getRequest().getHeaders().getFirst(HEADER));
    }

    /** The trimmed value when it is safe to log, forward and echo; otherwise null (a new id is created). */
    public static String sanitize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        return SAFE_VALUE.matcher(trimmed).matches() ? trimmed : null;
    }
}
