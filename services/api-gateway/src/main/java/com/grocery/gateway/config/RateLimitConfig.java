package com.grocery.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Rate-limit keys for Redis token buckets (RequestRateLimiter):
 * <ul>
 *   <li>{@code userOrIpKeyResolver}: per user for authenticated calls, per client IP otherwise;</li>
 *   <li>{@code ipKeyResolver}: per client IP — login/registration brute-force protection.</li>
 * </ul>
 * The client IP is the {@code X-Forwarded-For} entry added by the outermost proxy we run:
 * {@code grocery.gateway.rate-limit.trusted-proxies} counts the proxies in front of the gateway that append to that
 * header. Entries further left are written by the client and are never trusted, so a client can't pick its own bucket.
 * <ul>
 *   <li>{@code 0}: the header is ignored and the TCP peer address is used (gateway reachable only directly);</li>
 *   <li>{@code 1} (default, local): one proxy, e.g. the load balancer — or nothing, then the peer address;</li>
 *   <li>{@code 2} (AWS): CloudFront appends the client, the ALB appends CloudFront.</li>
 * </ul>
 * The gateway must only be reachable through those proxies (in AWS: an internal ALB behind CloudFront); a caller
 * that can reach it directly can still send a forged header.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    private final RemoteAddressResolver clientIpResolver;

    public RateLimitConfig(@Value("${grocery.gateway.rate-limit.trusted-proxies:1}") int trustedProxies) {
        this.clientIpResolver = clientIpResolver(trustedProxies);
    }

    static RemoteAddressResolver clientIpResolver(int trustedProxies) {
        if (trustedProxies < 0) {
            throw new IllegalArgumentException("grocery.gateway.rate-limit.trusted-proxies must be 0 or more, was " + trustedProxies);
        }
        return trustedProxies == 0 ? new RemoteAddressResolver() { } : XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxies);
    }

    @Bean
    @Primary
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    @Bean
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + clientIp(exchange));
    }

    String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = clientIpResolver.resolve(exchange);
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }
}
