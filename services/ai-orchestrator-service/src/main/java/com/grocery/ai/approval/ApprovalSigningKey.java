package com.grocery.ai.approval;

import com.grocery.ai.config.AiProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The RSA key that signs approval tokens (contract §5). From {@code AI_APPROVAL_SIGNING_KEY} (an unencrypted PKCS#8
 * PEM, {@code -----BEGIN PRIVATE KEY-----}; production: the same key for every replica, from the secrets store) or,
 * without it, generated at startup (fine for one local replica). The key id is the RFC 7638 thumbprint, so every
 * replica holding the same key publishes the same {@code kid}.
 */
@Component
public class ApprovalSigningKey {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSigningKey.class);

    private final RSAKey key;

    public ApprovalSigningKey(AiProperties properties) {
        String pem = properties.approvals().signingKey();
        try {
            if (pem == null || pem.isBlank()) {
                key = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                        .keyIDFromThumbprint(true).generate();
                log.warn("AI_APPROVAL_SIGNING_KEY is not set: generated an approval signing key (kid {}). Fine for "
                        + "one local replica; replicas must share a key from the secrets store.", key.getKeyID());
            } else {
                key = fromPem(pem);
                log.info("Approval signing key loaded from AI_APPROVAL_SIGNING_KEY (kid {})", key.getKeyID());
            }
        } catch (JOSEException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("AI_APPROVAL_SIGNING_KEY must be an unencrypted RSA private key in PKCS#8 "
                    + "PEM form (-----BEGIN PRIVATE KEY-----): " + e.getMessage(), e);
        }
    }

    static RSAKey fromPem(String pem) throws GeneralSecurityException, JOSEException {
        String base64 = pem.replace("\\n", "\n")
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (!(factory.generatePrivate(new PKCS8EncodedKeySpec(der)) instanceof RSAPrivateCrtKey privateKey)) {
            throw new IllegalArgumentException("not an RSA private key with CRT parameters");
        }
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint()
                .build();
    }

    /** The private key (signing only, never published). */
    RSAKey key() {
        return key;
    }

    public String keyId() {
        return key.getKeyID();
    }

    /** {@code GET /.well-known/approval-jwks.json}: public part only. */
    public JWKSet publicJwks() {
        return new JWKSet(key.toPublicJWK());
    }
}
