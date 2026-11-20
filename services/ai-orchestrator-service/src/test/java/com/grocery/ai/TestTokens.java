package com.grocery.ai;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Real signed access tokens for the tests that talk HTTP to the running server (the chat stream): an RSA key and a
 * stub JWKS endpoint, so the service validates tokens exactly as it does against identity-service.
 */
public final class TestTokens {

    static final String ISSUER = "https://identity.grocery.local";

    private final RSAKey signingKey;
    private final HttpServer server;

    private TestTokens(RSAKey signingKey, HttpServer server) {
        this.signingKey = signingKey;
        this.server = server;
    }

    static TestTokens start() {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            server.start();
            return new TestTokens(key, server);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException("Cannot start the test JWKS server", e);
        }
    }

    String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /** A token valid for 10 minutes; name claim "Asha <first 8 of id>". */
    public String token(UUID userId, String... roles) {
        Instant issuedAt = Instant.now().minusSeconds(1);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience("grocery-api")
                .subject(userId.toString())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(Duration.ofMinutes(10))))
                .claim("email", "user-" + userId.toString().substring(0, 8) + "@example.com")
                .claim("name", "Asha " + userId.toString().substring(0, 8))
                .claim("roles", List.of(roles))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        try {
            jwt.sign(new RSASSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}
