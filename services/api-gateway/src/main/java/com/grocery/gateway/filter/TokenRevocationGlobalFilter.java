package com.grocery.gateway.filter;

import com.grocery.gateway.security.ProblemDetailResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Rejects access tokens issued before the user's latest security-relevant change (suspension,
 * approval, role change). identity-service writes {@code auth:revoked-before:<userId>} = epoch millis;
 * a token with an older {@code iat_ms} gets 401 TOKEN_REVOKED and the client refreshes into a token
 * with current claims (or is refused).
 *
 * <p>If Redis is unavailable we fail OPEN (log and continue): revoked tokens then live until expiry
 * (≤ 15 minutes) instead of the whole platform going down with Redis.
 */
@Component
public class TokenRevocationGlobalFilter implements GlobalFilter, Ordered {

    static final String KEY_PREFIX = "auth:revoked-before:";
    private static final Logger log = LoggerFactory.getLogger(TokenRevocationGlobalFilter.class);

    private final ReactiveStringRedisTemplate redis;
    private final ProblemDetailResponses problems;

    public TokenRevocationGlobalFilter(ReactiveStringRedisTemplate redis, ProblemDetailResponses problems) {
        this.redis = redis;
        this.problems = problems;
    }

    /**
     * Decide first (a non-empty {@code Mono<Boolean>}), then continue the chain exactly once.
     * Never put {@code chain.filter(...)} before a {@code switchIfEmpty}: it returns an EMPTY
     * {@code Mono<Void>}, which would trigger the fallback and run the rest of the chain twice.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken())
                .flatMap(this::isRevoked)
                .defaultIfEmpty(false)                       // anonymous requests are never "revoked"
                .flatMap(revoked -> revoked
                        ? problems.write(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED",
                                "Your access has changed. Refresh the session.")
                        : chain.filter(exchange));
    }

    private Mono<Boolean> isRevoked(Jwt jwt) {
        long issuedAtMillis = issuedAtMillis(jwt);
        return redis.opsForValue().get(KEY_PREFIX + jwt.getSubject())
                .map(revokedBefore -> issuedAtMillis < Long.parseLong(revokedBefore))
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.error("Revocation check unavailable (failing open): {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    private static long issuedAtMillis(Jwt jwt) {
        Object millis = jwt.getClaims().get("iat_ms");
        if (millis instanceof Number number) {
            return number.longValue();
        }
        Instant issuedAt = jwt.getIssuedAt();
        return issuedAt == null ? 0 : issuedAt.toEpochMilli();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;   // right after the correlation-ID filter
    }
}
