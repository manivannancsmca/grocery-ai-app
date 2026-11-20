package com.grocery.common.approval;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Test-only stand-in for ai-orchestrator-service's approval signer: an RS256 key pair and a builder for approval
 * tokens whose every claim can be changed (never shipped in the library jar).
 */
final class TestApprovalSigner {

    static final String AUDIENCE = "order-service";

    private final RSAKey key;

    private TestApprovalSigner(RSAKey key) {
        this.key = key;
    }

    static TestApprovalSigner generate(String kid) {
        try {
            return new TestApprovalSigner(new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The public half, as published in the JWKS. */
    RSAKey publicKey() {
        return key.toPublicJWK();
    }

    String kid() {
        return key.getKeyID();
    }

    Token token(UUID approver) {
        return new Token(approver);
    }

    /** One approval token; defaults are a genuine PLACE_ORDER for {@code POST /api/orders} with an empty body. */
    final class Token {
        private final UUID id = UUID.randomUUID();
        private UUID approver;
        private UUID onBehalfOf;
        private String issuer = ApprovalToken.DEFAULT_ISSUER;
        private List<String> audiences = List.of(AUDIENCE);
        private String action = "PLACE_ORDER";
        private String method = "POST";
        private String path = "/api/orders";
        private String bodyHash = ApprovalTokenVerifier.bodyHash(new byte[0]);
        private Instant issuedAt = Instant.now();
        private Duration lifetime = Duration.ofSeconds(60);
        private String type = ApprovalToken.TYPE;
        private String kid = key.getKeyID();
        private Consumer<JWTClaimsSet.Builder> extra = builder -> {
        };

        private Token(UUID approver) {
            this.approver = approver;
            this.onBehalfOf = approver;
        }

        UUID id() {
            return id;
        }

        Token approver(UUID value) {
            approver = value;
            return this;
        }

        Token onBehalfOf(UUID value) {
            onBehalfOf = value;
            return this;
        }

        Token issuer(String value) {
            issuer = value;
            return this;
        }

        Token audiences(String... values) {
            audiences = List.of(values);
            return this;
        }

        Token action(String value) {
            action = value;
            return this;
        }

        Token request(String httpMethod, String httpPath) {
            method = httpMethod;
            path = httpPath;
            return this;
        }

        Token body(String json) {
            bodyHash = ApprovalTokenVerifier.bodyHash(json.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        Token bodyHash(String value) {
            bodyHash = value;
            return this;
        }

        Token issuedAt(Instant value) {
            issuedAt = value;
            return this;
        }

        Token lifetime(Duration value) {
            lifetime = value;
            return this;
        }

        Token type(String value) {
            type = value;
            return this;
        }

        Token kid(String value) {
            kid = value;
            return this;
        }

        /** Anything else (remove or retype a claim). */
        Token claims(Consumer<JWTClaimsSet.Builder> change) {
            extra = change;
            return this;
        }

        JWTClaimsSet claimsSet() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audiences)
                    .jwtID(id.toString())
                    .subject(approver == null ? null : approver.toString())
                    .claim(ApprovalToken.ON_BEHALF_OF, onBehalfOf == null ? null : onBehalfOf.toString())
                    .claim(ApprovalToken.ACTION, action)
                    .claim(ApprovalToken.HTTP_METHOD, method)
                    .claim(ApprovalToken.HTTP_URI, path)
                    .claim(ApprovalToken.BODY_HASH, bodyHash)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(lifetime)));
            extra.accept(claims);
            return claims.build();
        }

        String sign() {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(type == null ? null : new JOSEObjectType(type))
                    .keyID(kid)
                    .build();
            SignedJWT jwt = new SignedJWT(header, claimsSet());
            try {
                jwt.sign(new RSASSASigner(key));
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
            return jwt.serialize();
        }
    }
}
