package com.grocery.identity.security;

import com.grocery.common.id.Ids;
import com.grocery.identity.entity.SigningKey;
import com.grocery.identity.repository.SigningKeyRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

/**
 * Owns the RS256 signing key. On first start it generates a 2048-bit key pair and stores it, so all
 * identity replicas (and restarts) sign with the same key and previously issued tokens stay valid.
 * The oldest active key signs; every active key is published in the JWKS (enables rotation later).
 */
@Service
public class SigningKeyService {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

    private final SigningKeyRepository repository;
    private final RSAKey signingKey;

    public SigningKeyService(SigningKeyRepository repository, TransactionTemplate transactionTemplate, Clock clock) {
        this.repository = repository;
        List<SigningKey> keys = repository.findByActiveTrueOrderByCreatedAtAsc();
        if (keys.isEmpty()) {
            SigningKey generated = generate(clock);
            transactionTemplate.executeWithoutResult(tx -> repository.save(generated));
            log.info("Generated new JWT signing key {}", generated.getKid());
            keys = repository.findByActiveTrueOrderByCreatedAtAsc();
        }
        this.signingKey = toRsaKey(keys.getFirst(), true);
    }

    /** Private + public key used by the token encoder. */
    public RSAKey signingKey() {
        return signingKey;
    }

    public RSAPublicKey signingPublicKey() {
        try {
            return signingKey.toRSAPublicKey();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Invalid signing key", e);
        }
    }

    /** Public keys only — safe to publish. */
    public JWKSet publicJwkSet() {
        return new JWKSet(repository.findByActiveTrueOrderByCreatedAtAsc().stream()
                .map(key -> (com.nimbusds.jose.jwk.JWK) toRsaKey(key, false))
                .toList());
    }

    private static SigningKey generate(Clock clock) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Base64.Encoder b64 = Base64.getEncoder();
            return new SigningKey("key-" + Ids.newId(),
                    b64.encodeToString(pair.getPublic().getEncoded()),
                    b64.encodeToString(pair.getPrivate().getEncoded()),
                    clock.instant());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate RSA key pair", e);
        }
    }

    private static RSAKey toRsaKey(SigningKey key, boolean includePrivate) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            Base64.Decoder b64 = Base64.getDecoder();
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(b64.decode(key.getPublicKeyBase64())));
            RSAKey.Builder builder = new RSAKey.Builder(publicKey).keyID(key.getKid()).keyUse(KeyUse.SIGNATURE)
                    .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256);
            if (includePrivate) {
                builder.privateKey((RSAPrivateKey) factory.generatePrivate(
                        new PKCS8EncodedKeySpec(b64.decode(key.getPrivateKeyBase64()))));
            }
            return builder.build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Stored signing key " + key.getKid() + " is unreadable", e);
        }
    }
}
