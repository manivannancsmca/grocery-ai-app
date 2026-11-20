package com.grocery.common.approval;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The {@code X-Approval-Token} vocabulary (contract Phase 11 §5): a JWS (RS256, header {@code typ: approval+jwt} and
 * {@code kid}) signed by ai-orchestrator-service that binds one human approval to exactly one HTTP request.
 *
 * <table>
 *   <tr><th>Claim</th><th>Value</th></tr>
 *   <tr><td>{@code iss}</td><td>{@code ai-orchestrator-service}</td></tr>
 *   <tr><td>{@code aud}</td><td>the target service ({@code order-service}, ...)</td></tr>
 *   <tr><td>{@code jti}</td><td>the approval id (UUID)</td></tr>
 *   <tr><td>{@code sub}</td><td>the approver's user id; equals the request JWT's {@code sub}</td></tr>
 *   <tr><td>{@code obo}</td><td>the customer the action is for (absent for admin actions)</td></tr>
 *   <tr><td>{@code act}</td><td>the action ({@code PLACE_ORDER}, ...)</td></tr>
 *   <tr><td>{@code htm}, {@code htu}</td><td>the exact HTTP method and path</td></tr>
 *   <tr><td>{@code bsh}</td><td>base64url SHA-256 of the exact body bytes</td></tr>
 *   <tr><td>{@code iat}, {@code exp}</td><td>{@code exp = iat + 60 s}</td></tr>
 * </table>
 */
public final class ApprovalToken {

    /** The request header carrying the token. */
    public static final String HEADER = "X-Approval-Token";
    /** The JOSE header {@code typ}. */
    public static final String TYPE = "approval+jwt";
    /** The only issuer of approval tokens. */
    public static final String DEFAULT_ISSUER = "ai-orchestrator-service";

    public static final String ON_BEHALF_OF = "obo";
    public static final String ACTION = "act";
    public static final String HTTP_METHOD = "htm";
    public static final String HTTP_URI = "htu";
    public static final String BODY_HASH = "bsh";

    private static final Pattern ACTION_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private ApprovalToken() {
    }

    /** {@code true} for a well-formed action name (upper case, digits, underscores; at most 40 characters). */
    static boolean isActionName(String value) {
        return value != null && ACTION_NAME.matcher(value).matches();
    }

    /** The UUID in {@code value}, or {@code null} when it is not one. */
    static UUID uuidOrNull(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The approval id and action a token <em>claims</em>, read without any verification and only for log lines (both
     * {@code null} unless well-formed, so nothing an attacker controls reaches the log).
     */
    record Claimed(UUID approvalId, String action) {

        static final Claimed NONE = new Claimed(null, null);

        static Claimed peek(String token) {
            if (token == null || token.isBlank()) {
                return NONE;
            }
            try {
                JWTClaimsSet claims = SignedJWT.parse(token.strip()).getJWTClaimsSet();
                Object action = claims.getClaim(ACTION);
                return new Claimed(uuidOrNull(claims.getJWTID()),
                        action instanceof String name && isActionName(name) ? name : null);
            } catch (Exception e) {
                return NONE;
            }
        }
    }
}
