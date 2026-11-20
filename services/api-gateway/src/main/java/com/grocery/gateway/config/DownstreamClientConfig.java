package com.grocery.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * How the gateway's HTTP client finds the services behind it.
 *
 * <p>Reactor Netty caches DNS answers for their TTL. Docker's embedded DNS (and some cloud resolvers) answer with
 * long TTLs, so after a service container or task is replaced — new IP address, same name — the gateway kept
 * connecting to the old, dead address for minutes (connect timeouts → 503). Service discovery by DNS needs short
 * caching: answers are kept for at most {@code grocery.gateway.dns-cache-max-ttl} (5 s), failures for 1 s.
 * A lookup inside the Docker network or a VPC takes well under a millisecond, so this costs nothing measurable.
 */
@Configuration(proxyBeanMethods = false)
public class DownstreamClientConfig {

    @Bean
    HttpClientCustomizer shortDnsCacheCustomizer(
            @Value("${grocery.gateway.dns-cache-max-ttl:5s}") Duration maxTtl) {
        return httpClient -> httpClient.resolver(spec -> spec
                .cacheMaxTimeToLive(maxTtl)
                .cacheNegativeTimeToLive(Duration.ofSeconds(1)));
    }
}
