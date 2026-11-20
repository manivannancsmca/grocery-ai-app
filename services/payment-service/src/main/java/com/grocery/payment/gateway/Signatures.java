package com.grocery.payment.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Razorpay's HMAC-SHA256 signatures (lower-case hex):
 * <ul>
 *   <li>Checkout result: {@code hex(HMAC_SHA256(razorpayOrderId + "|" + razorpayPaymentId, keySecret))}</li>
 *   <li>Webhook: {@code hex(HMAC_SHA256(rawBody, webhookSecret))} — over the exact bytes received, before any
 *       parsing (re-serialised JSON would not match).</li>
 * </ul>
 * Comparisons take constant time ({@link MessageDigest#isEqual}).
 */
public final class Signatures {

    private Signatures() {
    }

    public static String hmacHex(String secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public static String checkout(String razorpayOrderId, String razorpayPaymentId, String keySecret) {
        return hmacHex(keySecret, (razorpayOrderId + "|" + razorpayPaymentId).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean checkoutMatches(String razorpayOrderId, String razorpayPaymentId, String signature,
                                          String keySecret) {
        if (isBlank(keySecret) || razorpayOrderId == null || razorpayPaymentId == null) {
            return false;
        }
        return matches(checkout(razorpayOrderId, razorpayPaymentId, keySecret), signature);
    }

    /** False when the secret is not configured: no webhook is accepted then. */
    public static boolean webhookMatches(byte[] rawBody, String signature, String webhookSecret) {
        if (isBlank(webhookSecret) || rawBody == null) {
            return false;
        }
        return matches(hmacHex(webhookSecret, rawBody), signature);
    }

    /** Constant-time comparison of a computed hex digest with the received one (case-insensitive hex). */
    static boolean matches(String expectedHex, String receivedHex) {
        if (receivedHex == null) {
            return false;
        }
        byte[] expected = expectedHex.getBytes(StandardCharsets.US_ASCII);
        byte[] received = receivedHex.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, received);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
