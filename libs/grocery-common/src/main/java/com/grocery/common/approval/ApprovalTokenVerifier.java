package com.grocery.common.approval;

import com.grocery.common.approval.ApprovalTokenException.Reason;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Verifies an {@code X-Approval-Token} against the request it arrived with, in the order of contract Phase 11 §5:
 * <ol>
 *   <li>a well-formed RS256 JWS whose signature verifies with a key of the JWKS (by {@code kid}; an unknown
 *       {@code kid} refetches the JWKS once), {@code typ} = {@code approval+jwt} — else {@code SIGNATURE};
 *       JWKS unreachable — {@code APPROVAL_KEYS_UNAVAILABLE};</li>
 *   <li>{@code iss} = the orchestrator and {@code aud} = exactly this service — else {@code AUDIENCE};
 *       not expired and not issued in the future (with the clock skew) — else {@code EXPIRED};</li>
 *   <li>{@code htm} / {@code htu} = the request's method and path — else {@code METHOD_OR_PATH};</li>
 *   <li>{@code bsh} = base64url(SHA-256(body bytes)) — else {@code APPROVAL_BODY_MISMATCH} (the body is read only
 *       now, after the token is known to be genuine);</li>
 *   <li>{@code sub} = the authenticated user — else {@code SUBJECT}.</li>
 * </ol>
 * Single use ({@code consumed_approval}) is the caller's next step ({@link ConsumedApprovals}).
 */
public class ApprovalTokenVerifier implements AutoCloseable {

    /** The request body, read only when the token got that far. */
    @FunctionalInterface
    public interface BodySource {
        byte[] read() throws IOException;
    }

    private final JWKSource<SecurityContext> keys;
    private final String issuer;
    private final String audience;
    private final Duration clockSkew;
    private final Clock clock;

    public ApprovalTokenVerifier(JWKSource<SecurityContext> keys, String issuer, String audience, Duration clockSkew,
                                 Clock clock) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("issuer and audience are required");
        }
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        this.clockSkew = clockSkew == null || clockSkew.isNegative() ? Duration.ZERO : clockSkew;
        this.clock = clock;
    }

    /**
     * The orchestrator's JWKS over HTTP: cached for {@code cacheTtl}, refetched when a token names an unknown
     * {@code kid} (at most once per {@code minRefetchInterval}; {@code 0} = no limit), no outage tolerance (an
     * expired cache that cannot be refreshed means {@code APPROVAL_KEYS_UNAVAILABLE}).
     */
    public static JWKSource<SecurityContext> remoteKeys(URL jwksUri, Duration timeout, Duration cacheTtl,
                                                        Duration minRefetchInterval) {
        int timeoutMillis = (int) Math.clamp(timeout.toMillis(), 100, 60_000);
        long ttl = cacheTtl.toMillis();
        long minInterval = minRefetchInterval == null ? 0 : minRefetchInterval.toMillis();
        if (ttl <= 0) {
            throw new IllegalArgumentException("grocery.approvals.jwks-cache-ttl must be positive");
        }
        if (minInterval > 0 && minInterval >= ttl) {
            throw new IllegalArgumentException("grocery.approvals.jwks-min-refetch-interval (" + minRefetchInterval
                    + ") must be shorter than grocery.approvals.jwks-cache-ttl (" + cacheTtl + ")");
        }
        JWKSourceBuilder<SecurityContext> builder = JWKSourceBuilder.<SecurityContext>create(jwksUri,
                        new DefaultResourceRetriever(timeoutMillis, timeoutMillis,
                                JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT))
                .cache(ttl, 2L * timeoutMillis + 1_000)
                .refreshAheadCache(false)
                .retrying(false)
                .outageTolerant(false);
        builder = minInterval > 0 ? builder.rateLimited(minInterval) : builder.rateLimited(false);
        return builder.build();
    }

    /**
     * @param token   the header value
     * @param method  the request method
     * @param target  the request path as the service received it (plus {@code ?query} if the request has one)
     * @param body    the request body bytes
     * @param subject the authenticated user's {@code sub}, {@code null} when unauthenticated
     * @return the verified approval (not yet consumed)
     * @throws ApprovalTokenException when the token is refused
     * @throws IOException            when the body cannot be read
     */
    public ApprovalContext verify(String token, String method, String target, BodySource body, String subject)
            throws IOException {
        // 1. signature (key by kid from the JWKS) and type
        SignedJWT jwt = parse(token);
        verifySignature(jwt);
        verifyType(jwt.getHeader());
        JWTClaimsSet claims;
        UUID approvalId;
        String action;
        UUID onBehalfOf;
        try {
            claims = jwt.getJWTClaimsSet();
            approvalId = ApprovalToken.uuidOrNull(claims.getJWTID());
            action = claims.getStringClaim(ApprovalToken.ACTION);
            String obo = claims.getStringClaim(ApprovalToken.ON_BEHALF_OF);
            onBehalfOf = obo == null ? null : ApprovalToken.uuidOrNull(obo);
            if (approvalId == null || !ApprovalToken.isActionName(action) || (obo != null && onBehalfOf == null)) {
                throw ApprovalTokenException.invalid(Reason.SIGNATURE,
                        "jti must be a UUID, act an action name and obo (if present) a UUID");
            }
        } catch (ParseException e) {
            throw ApprovalTokenException.invalid(Reason.SIGNATURE, "malformed claims: " + e.getMessage());
        }

        // 2. issuer, audience, lifetime
        if (!issuer.equals(claims.getIssuer())) {
            throw ApprovalTokenException.invalid(Reason.AUDIENCE, "iss " + claims.getIssuer() + " is not " + issuer);
        }
        List<String> audiences = claims.getAudience();
        if (audiences.size() != 1 || !audience.equals(audiences.getFirst())) {
            throw ApprovalTokenException.invalid(Reason.AUDIENCE, "aud " + audiences + " is not [" + audience + "]");
        }
        verifyLifetime(claims);

        // 3. method and path
        String htm = string(claims, ApprovalToken.HTTP_METHOD, Reason.METHOD_OR_PATH);
        String htu = string(claims, ApprovalToken.HTTP_URI, Reason.METHOD_OR_PATH);
        if (!method.equals(htm) || !target.equals(htu)) {
            throw ApprovalTokenException.invalid(Reason.METHOD_OR_PATH,
                    "token is for " + htm + " " + htu + ", request is " + method + " " + target);
        }

        // 4. body hash
        verifyBody(claims, body.read());

        // 5. the approver is the authenticated user
        String approver = claims.getSubject();
        UUID approverId = ApprovalToken.uuidOrNull(approver);
        if (approver == null || !approver.equals(subject) || approverId == null) {
            throw ApprovalTokenException.invalid(Reason.SUBJECT,
                    "sub " + approver + " is not the authenticated user " + subject);
        }
        return new ApprovalContext(approvalId, action, approverId, onBehalfOf);
    }

    private static SignedJWT parse(String token) {
        if (token == null || token.isBlank()) {
            throw ApprovalTokenException.invalid(Reason.SIGNATURE, "empty token");
        }
        try {
            return SignedJWT.parse(token.strip());
        } catch (ParseException | RuntimeException e) {
            throw ApprovalTokenException.invalid(Reason.SIGNATURE, "not a JWS");
        }
    }

    private void verifySignature(SignedJWT jwt) {
        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) {
            throw ApprovalTokenException.invalid(Reason.SIGNATURE, "alg " + header.getAlgorithm() + " is not RS256");
        }
        String kid = header.getKeyID();
        if (kid == null || kid.isBlank()) {
            throw ApprovalTokenException.invalid(Reason.SIGNATURE, "no kid");
        }
        List<JWK> candidates;
        try {
            candidates = keys.get(new JWKSelector(JWKMatcher.forJWSHeader(header)), null);
        } catch (RateLimitReachedException e) {
            throw ApprovalTokenException.invalid(Reason.SIGNATURE,
                    "unknown kid " + kid + " (JWKS refetched shortly before; rate limited)");
        } catch (KeySourceException e) {
            throw ApprovalTokenException.keysUnavailable("JWKS unavailable: " + e.getMessage(), e);
        }
        for (JWK candidate : candidates) {
            if (candidate instanceof RSAKey rsaKey) {
                try {
                    if (jwt.verify(new RSASSAVerifier(rsaKey))) {
                        return;
                    }
                } catch (JOSEException e) {
                    // unusable key: try the next one
                }
            }
        }
        throw ApprovalTokenException.invalid(Reason.SIGNATURE,
                candidates.isEmpty() ? "unknown kid " + kid : "signature does not verify with kid " + kid);
    }

    private static void verifyType(JWSHeader header) {
        JOSEObjectType type = header.getType();
        String value = type == null ? "" : type.getType().toLowerCase(Locale.ROOT);
        if (!value.equals(ApprovalToken.TYPE) && !value.equals("application/" + ApprovalToken.TYPE)) {
            throw ApprovalTokenException.invalid(Reason.SIGNATURE, "typ " + type + " is not " + ApprovalToken.TYPE);
        }
    }

    private void verifyLifetime(JWTClaimsSet claims) {
        Instant now = clock.instant();
        Date expiresAt = claims.getExpirationTime();
        Date issuedAt = claims.getIssueTime();
        Date notBefore = claims.getNotBeforeTime();
        if (expiresAt == null || issuedAt == null) {
            throw ApprovalTokenException.invalid(Reason.EXPIRED, "exp and iat are required");
        }
        if (now.isAfter(expiresAt.toInstant().plus(clockSkew))) {
            throw ApprovalTokenException.invalid(Reason.EXPIRED, "expired at " + expiresAt.toInstant());
        }
        Instant latestStart = now.plus(clockSkew);
        if (issuedAt.toInstant().isAfter(latestStart) || (notBefore != null && notBefore.toInstant().isAfter(latestStart))) {
            throw ApprovalTokenException.invalid(Reason.EXPIRED, "issued in the future (" + issuedAt.toInstant() + ")");
        }
    }

    private static void verifyBody(JWTClaimsSet claims, byte[] body) {
        byte[] expected;
        try {
            String bsh = claims.getStringClaim(ApprovalToken.BODY_HASH);
            if (bsh == null) {
                throw ApprovalTokenException.bodyMismatch("no bsh");
            }
            expected = Base64.getUrlDecoder().decode(bsh);
        } catch (ParseException | IllegalArgumentException e) {
            throw ApprovalTokenException.bodyMismatch("bsh is not base64url");
        }
        if (!MessageDigest.isEqual(expected, sha256(body))) {
            throw ApprovalTokenException.bodyMismatch("SHA-256 of the " + body.length + "-byte body differs from bsh");
        }
    }

    /** {@code base64url(SHA-256(body))} without padding, as in the {@code bsh} claim. */
    public static String bodyHash(byte[] body) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(body));
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String string(JWTClaimsSet claims, String name, Reason reason) {
        try {
            String value = claims.getStringClaim(name);
            if (value == null) {
                throw ApprovalTokenException.invalid(reason, "no " + name);
            }
            return value;
        } catch (ParseException e) {
            throw ApprovalTokenException.invalid(reason, name + " is not a string");
        }
    }

    public String audience() {
        return audience;
    }

    @Override
    public void close() throws IOException {
        if (keys instanceof Closeable closeable) {
            closeable.close();
        }
    }
}
