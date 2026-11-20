package com.grocery.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which address the rate-limit buckets use, for each number of trusted proxies (security scan finding, Phase 13). */
class RateLimitConfigTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("10.0.5.9", 51234);

    private static MockServerWebExchange exchange(String xForwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/api/auth/login").remoteAddress(PEER);
        if (xForwardedFor != null) {
            request.header("X-Forwarded-For", xForwardedFor);
        }
        return MockServerWebExchange.from(request);
    }

    @Test
    void behindCloudFrontAndAlbTheClientCannotChooseItsBucket() {
        RateLimitConfig config = new RateLimitConfig(2);
        // client-sent entry, then CloudFront appends the client, then the ALB appends CloudFront
        assertThat(config.clientIp(exchange("1.2.3.4, 203.0.113.7, 130.176.10.20"))).isEqualTo("203.0.113.7");
        assertThat(config.clientIp(exchange("9.9.9.9, 203.0.113.7, 130.176.10.20"))).isEqualTo("203.0.113.7");
        assertThat(config.clientIp(exchange("203.0.113.7, 130.176.10.20"))).isEqualTo("203.0.113.7");
    }

    @Test
    void oneProxyTrustsOnlyTheLastEntry() {
        RateLimitConfig config = new RateLimitConfig(1);
        assertThat(config.clientIp(exchange("1.2.3.4, 198.51.100.8"))).isEqualTo("198.51.100.8");
        assertThat(config.clientIp(exchange(null))).isEqualTo("10.0.5.9");
    }

    @Test
    void zeroProxiesIgnoresTheHeader() {
        RateLimitConfig config = new RateLimitConfig(0);
        assertThat(config.clientIp(exchange("1.2.3.4"))).isEqualTo("10.0.5.9");
        assertThat(config.clientIp(exchange(null))).isEqualTo("10.0.5.9");
    }

    @Test
    void aNegativeCountIsAConfigurationError() {
        assertThatThrownBy(() -> new RateLimitConfig(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
