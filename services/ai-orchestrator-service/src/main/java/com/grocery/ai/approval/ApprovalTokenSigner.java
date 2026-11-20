package com.grocery.ai.approval;

import com.grocery.ai.config.AiProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Signs the {@code X-Approval-Token} of one execution (contract §5): a JWS (RS256, {@code typ: approval+jwt}, {@code kid})
 * whose claims bind the approval to the exact call — audience (target service), method and path, the SHA-256 of the
 * body fixed at proposal time, the approver ({@code sub}, equal to the forwarded JWT's subject) and the customer it is
 * for ({@code obo}). {@code jti} = the approval id, which the target consumes once. Valid for 60 s.
 */
@Component
public class ApprovalTokenSigner {

    public static final JOSEObjectType TYPE = new JOSEObjectType("approval+jwt");

    private final ApprovalSigningKey signingKey;
    private final String issuer;
    private final long ttlSeconds;
    private final Clock clock;
    private final RSASSASigner signer;

    public ApprovalTokenSigner(ApprovalSigningKey signingKey, AiProperties properties, Clock clock) {
        this.signingKey = signingKey;
        this.issuer = properties.approvals().issuer();
        this.ttlSeconds = properties.approvals().tokenTtl().toSeconds();
        this.clock = clock;
        try {
            this.signer = new RSASSASigner(signingKey.key());
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot create the approval token signer", e);
        }
    }

    /** The token for executing {@code approval} with the authority of {@code approverId}. */
    public String sign(ApprovalRequest approval, UUID approverId) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(approval.getTargetService())
                .jwtID(approval.getId().toString())
                .subject(approverId.toString())
                .claim("act", approval.getAction().name())
                .claim("htm", approval.getHttpMethod())
                .claim("htu", approval.getHttpPath())
                .claim("bsh", bodyHash(approval.getBodySha256()))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plusSeconds(ttlSeconds)));
        if (approval.getOnBehalfOf() != null) {
            claims.claim("obo", approval.getOnBehalfOf().toString());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(TYPE)
                .keyID(signingKey.keyId())
                .build(), claims.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the approval token", e);
        }
        return jwt.serialize();
    }

    /** {@code bsh}: base64url (no padding) of the SHA-256 stored as hex at proposal time. */
    static String bodyHash(String sha256Hex) {
        return Base64URL.encode(HexFormat.of().parseHex(sha256Hex)).toString();
    }
}
