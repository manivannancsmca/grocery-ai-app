package com.grocery.apitests.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/** Reads and forges JWTs (the attacker's side of the JWT-tampering tests). */
public final class Jwt {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Jwt() {
    }

    public static JsonNode header(String token) {
        return Json.read(new String(B64D.decode(token.split("\\.")[0]), StandardCharsets.UTF_8));
    }

    public static JsonNode claims(String token) {
        return Json.read(new String(B64D.decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    public static String claim(String token, String name) {
        JsonNode value = claims(token).get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    public static String b64(String text) {
        return B64.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String b64(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    /** Replaces the payload, keeps the original header and signature. */
    public static String withPayload(String token, Map<String, Object> claims) {
        String[] parts = token.split("\\.");
        return parts[0] + "." + b64(Json.write(claims)) + "." + parts[2];
    }

    /** Flips bits in the signature. */
    public static String withBrokenSignature(String token) {
        String[] parts = token.split("\\.");
        byte[] signature = B64D.decode(parts[2]);
        signature[signature.length / 2] ^= 0x5A;
        signature[0] ^= 0x01;
        return parts[0] + "." + parts[1] + "." + b64(signature);
    }

    /** {@code alg: none}, no signature. */
    public static String unsigned(Map<String, Object> claims) {
        return b64(Json.write(Map.of("alg", "none", "typ", "JWT"))) + "." + b64(Json.write(claims)) + ".";
    }

    /** HS256 with an attacker-chosen secret (the classic RS256 → HS256 "algorithm confusion" attempt). */
    public static String hs256(Map<String, Object> header, Map<String, Object> claims, byte[] secret) {
        try {
            String signingInput = b64(Json.write(header)) + "." + b64(Json.write(claims));
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            return signingInput + "." + b64(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** RS256 signed with the attacker's own key. */
    public static String rs256(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
        try {
            String signingInput = b64(Json.write(header)) + "." + b64(Json.write(claims));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair attackerKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
