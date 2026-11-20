package com.grocery.identity.security;

import com.grocery.identity.config.IdentityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Makes a user's EXISTING access tokens unusable at once (suspension, role or business approval
 * changes) by writing {@code auth:revoked-before:<userId> = <epoch millis>} to Redis. The gateway
 * rejects tokens whose {@code iat_ms} is older with 401 TOKEN_REVOKED; the client then refreshes
 * and receives a token with the current claims (or is refused if suspended).
 *
 * <p>The marker only needs to outlive the access tokens it invalidates, so it expires with them.
 */
@Service
public class TokenRevocationService {

    public static final String KEY_PREFIX = "auth:revoked-before:";
    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final StringRedisTemplate redis;
    private final Duration markerTtl;
    private final Clock clock;

    public TokenRevocationService(StringRedisTemplate redis, IdentityProperties properties, Clock clock) {
        this.redis = redis;
        this.markerTtl = properties.accessTokenTtl().plusMinutes(1);
        this.clock = clock;
    }

    /** Applied after the surrounding transaction commits (never revoke for a change that rolled back). */
    public void revokeAccessTokensIssuedBeforeNow(UUID userId) {
        long nowMillis = clock.millis();
        Runnable write = () -> {
            try {
                redis.opsForValue().set(KEY_PREFIX + userId, Long.toString(nowMillis), markerTtl);
            } catch (RuntimeException e) {
                // Degraded mode: tokens stay valid until they expire (≤ access-token TTL). Never fail the business change.
                log.error("Could not write revocation marker for user {}: {}", userId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }
}
