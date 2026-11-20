package com.grocery.common.approval;

import com.grocery.common.approval.ApprovalTokenException.Reason;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSetUnavailableException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** The checks of contract Phase 11 §5, their order and their answers, with an in-memory key set. */
class ApprovalTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final String BODY = "{\"addressId\":\"a-1\",\"note\":\"ring twice ✓\"}";
    private static final UUID APPROVER = UUID.fromString("0190f3a2-0000-7000-8000-000000000001");

    private final TestApprovalSigner signer = TestApprovalSigner.generate("orchestrator-1");
    private final TestApprovalSigner stranger = TestApprovalSigner.generate("orchestrator-1");   // same kid, other key
    private final ApprovalTokenVerifier verifier = verifier(new ImmutableJWKSet<>(new JWKSet(signer.publicKey())));

    private static ApprovalTokenVerifier verifier(JWKSource<SecurityContext> keys) {
        return new ApprovalTokenVerifier(keys, ApprovalToken.DEFAULT_ISSUER, TestApprovalSigner.AUDIENCE,
                Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TestApprovalSigner.Token token() {
        return signer.token(APPROVER).issuedAt(NOW).body(BODY);
    }

    private ApprovalContext verify(String token) throws Exception {
        return verify(token, "POST", "/api/orders", BODY, APPROVER.toString());
    }

    private ApprovalContext verify(String token, String method, String target, String body, String subject)
            throws Exception {
        return verifier.verify(token, method, target, () -> body.getBytes(StandardCharsets.UTF_8), subject);
    }

    private static ApprovalTokenException refusal(ThrowingCall call) {
        try {
            call.run();
        } catch (ApprovalTokenException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("expected an ApprovalTokenException", e);
        }
        throw new AssertionError("the token was accepted");
    }

    private void assertInvalid(String token, Reason reason) {
        ApprovalTokenException e = refusal(() -> verify(token));
        assertThat(e.errorCode()).isEqualTo("APPROVAL_TOKEN_INVALID");
        assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(e.reason()).isEqualTo(reason);
        assertThat(e.properties()).containsEntry("reason", reason.name());
    }

    @FunctionalInterface
    interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void aGenuineTokenGivesTheApproval() throws Exception {
        TestApprovalSigner.Token token = token();
        ApprovalContext approval = verify(token.sign());
        assertThat(approval).isEqualTo(new ApprovalContext(token.id(), "PLACE_ORDER", APPROVER, APPROVER));

        // admin actions have no obo; "application/approval+jwt" is the same type (RFC 8725 §3.11)
        TestApprovalSigner.Token admin = token().onBehalfOf(null).action("REFUND_PAYMENT").type("application/approval+jwt");
        assertThat(verify(admin.sign()))
                .isEqualTo(new ApprovalContext(admin.id(), "REFUND_PAYMENT", APPROVER, null));
    }

    @Test
    void theBodyHashIsSha256Base64UrlWithoutPadding() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        assertThat(ApprovalTokenVerifier.bodyHash(body)).isEqualTo(expected).doesNotContain("=", "+", "/");
        assertThat(ApprovalTokenVerifier.bodyHash(new byte[0])).isEqualTo("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU");
        // a padded value is the same hash
        assertThat(verify(token().bodyHash(expected + "=").sign())).isNotNull();
    }

    @Test
    void onlyAGenuineRs256ApprovalJwsPassesTheSignatureCheck() throws Exception {
        assertInvalid("", Reason.SIGNATURE);
        assertInvalid("not-a-token", Reason.SIGNATURE);
        assertInvalid(new PlainJWT(new PlainHeader(), token().claimsSet()).serialize(), Reason.SIGNATURE);

        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("approval+jwt"))
                .keyID(signer.kid()).build(), token().claimsSet());
        hmac.sign(new MACSigner(new byte[32]));
        assertInvalid(hmac.serialize(), Reason.SIGNATURE);

        assertInvalid(token().kid(null).sign(), Reason.SIGNATURE);                 // no kid
        assertInvalid(token().kid("unknown").sign(), Reason.SIGNATURE);            // kid not in the JWKS
        assertInvalid(stranger.token(APPROVER).issuedAt(NOW).body(BODY).sign(), Reason.SIGNATURE);   // other key

        String[] parts = token().sign().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                token().action("REFUND_PAYMENT").claimsSet().toString().getBytes(StandardCharsets.UTF_8));
        assertInvalid(parts[0] + "." + forgedPayload + "." + parts[2], Reason.SIGNATURE);   // claims changed

        assertInvalid(token().type("JWT").sign(), Reason.SIGNATURE);               // an access token is not an approval
        assertInvalid(token().type(null).sign(), Reason.SIGNATURE);
        assertInvalid(token().claims(c -> c.jwtID("approval-1")).sign(), Reason.SIGNATURE);
        assertInvalid(token().claims(c -> c.claim("act", null)).sign(), Reason.SIGNATURE);
        assertInvalid(token().claims(c -> c.claim("act", "place order; DROP")).sign(), Reason.SIGNATURE);
        assertInvalid(token().claims(c -> c.claim("obo", "customer-1")).sign(), Reason.SIGNATURE);
    }

    @Test
    void theTokenMustComeFromTheOrchestratorForThisService() {
        assertInvalid(token().audiences("payment-service").sign(), Reason.AUDIENCE);
        assertInvalid(token().audiences(TestApprovalSigner.AUDIENCE, "payment-service").sign(), Reason.AUDIENCE);
        assertInvalid(token().claims(c -> c.audience((String) null)).sign(), Reason.AUDIENCE);
        assertInvalid(token().issuer("identity-service").sign(), Reason.AUDIENCE);
        assertInvalid(token().issuer(null).sign(), Reason.AUDIENCE);
    }

    @Test
    void expiryAllowsFiveSecondsOfClockSkew() throws Exception {
        assertThat(verify(token().issuedAt(NOW.minusSeconds(64)).sign())).isNotNull();       // expired 4 s ago
        assertInvalid(token().issuedAt(NOW.minusSeconds(66)).sign(), Reason.EXPIRED);        // expired 6 s ago
        assertThat(verify(token().issuedAt(NOW.plusSeconds(4)).sign())).isNotNull();          // clocks 4 s apart
        assertInvalid(token().issuedAt(NOW.plusSeconds(30)).sign(), Reason.EXPIRED);         // issued in the future
        assertInvalid(token().claims(c -> c.expirationTime(null)).sign(), Reason.EXPIRED);
        assertInvalid(token().claims(c -> c.issueTime(null)).sign(), Reason.EXPIRED);
    }

    @Test
    void theTokenIsBoundToTheMethodAndPath() {
        String token = token().sign();
        for (String[] request : new String[][]{
                {"PUT", "/api/orders"}, {"post", "/api/orders"}, {"POST", "/api/orders/"},
                {"POST", "/api/orders/0190f3a2/cancel"}, {"POST", "/api/orders?dryRun=false"}, {"POST", "/API/orders"}}) {
            ApprovalTokenException e = refusal(() -> verify(token, request[0], request[1], BODY, APPROVER.toString()));
            assertThat(e.reason()).as("%s %s", request[0], request[1]).isEqualTo(Reason.METHOD_OR_PATH);
        }
        assertInvalid(token().claims(c -> c.claim("htu", null)).sign(), Reason.METHOD_OR_PATH);
        assertInvalid(token().claims(c -> c.claim("htm", 1)).sign(), Reason.METHOD_OR_PATH);
    }

    @Test
    void theBodyMustBeTheApprovedOne() {
        String token = token().sign();
        for (String body : new String[]{"{\"addressId\":\"a-2\",\"note\":\"ring twice ✓\"}", BODY + " ", "", BODY.replace("✓", "v")}) {
            ApprovalTokenException e = refusal(() -> verify(token, "POST", "/api/orders", body, APPROVER.toString()));
            assertThat(e.errorCode()).isEqualTo("APPROVAL_BODY_MISMATCH");
            assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.reason()).isNull();
            assertThat(e.properties()).isEmpty();
        }
        assertThat(refusal(() -> verify(token().claims(c -> c.claim("bsh", null)).sign())).errorCode())
                .isEqualTo("APPROVAL_BODY_MISMATCH");
        assertThat(refusal(() -> verify(token().bodyHash("not base64 ***").sign())).errorCode())
                .isEqualTo("APPROVAL_BODY_MISMATCH");
    }

    @Test
    void theApproverMustBeTheAuthenticatedUser() {
        String token = token().sign();
        assertThat(refusal(() -> verify(token, "POST", "/api/orders", BODY, UUID.randomUUID().toString())).reason())
                .isEqualTo(Reason.SUBJECT);
        assertThat(refusal(() -> verify(token, "POST", "/api/orders", BODY, null)).reason()).isEqualTo(Reason.SUBJECT);
        assertInvalid(token().approver(null).sign(), Reason.SUBJECT);
    }

    @Test
    void theChecksRunInTheContractsOrderAndTheBodyIsReadOnlyForAGenuineToken() {
        // wrong audience AND wrong path AND wrong body AND wrong user: the first check answers
        String token = token().audiences("payment-service").request("PUT", "/elsewhere").sign();
        ApprovalTokenException e = refusal(() -> verifier.verify(token, "POST", "/api/orders",
                () -> fail("the body must not be read"), "someone-else"));
        assertThat(e.reason()).isEqualTo(Reason.AUDIENCE);

        String forOtherPath = token().request("POST", "/api/orders/1/cancel").sign();
        assertThat(refusal(() -> verifier.verify(forOtherPath, "POST", "/api/orders",
                () -> fail("the body must not be read"), "someone-else")).reason()).isEqualTo(Reason.METHOD_OR_PATH);

        // body before subject (contract steps 4 and 5)
        assertThat(refusal(() -> verify(token().sign(), "POST", "/api/orders", "{}", "someone-else")).errorCode())
                .isEqualTo("APPROVAL_BODY_MISMATCH");
        // expiry before method and path
        assertThat(refusal(() -> verify(token().issuedAt(NOW.minusSeconds(600)).sign(), "PUT", "/x", BODY,
                APPROVER.toString())).reason()).isEqualTo(Reason.EXPIRED);
    }

    @Test
    void unreachableKeysAre503AndARateLimitedRefetchIsAnUnknownKey() {
        ApprovalTokenVerifier down = verifier((selector, context) -> {
            throw new JWKSetUnavailableException("connection refused");
        });
        ApprovalTokenException e = refusal(() -> down.verify(token().sign(), "POST", "/api/orders",
                () -> BODY.getBytes(StandardCharsets.UTF_8), APPROVER.toString()));
        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.errorCode()).isEqualTo("APPROVAL_KEYS_UNAVAILABLE");
        assertThat(e.reason()).isNull();

        ApprovalTokenVerifier otherFailure = verifier((selector, context) -> {
            throw new KeySourceException("bad JWKS");
        });
        assertThat(refusal(() -> otherFailure.verify(token().sign(), "POST", "/api/orders",
                () -> BODY.getBytes(StandardCharsets.UTF_8), APPROVER.toString())).errorCode())
                .isEqualTo("APPROVAL_KEYS_UNAVAILABLE");

        ApprovalTokenVerifier limited = verifier((selector, context) -> {
            throw new RateLimitReachedException();
        });
        assertThat(refusal(() -> limited.verify(token().sign(), "POST", "/api/orders",
                () -> BODY.getBytes(StandardCharsets.UTF_8), APPROVER.toString())).reason())
                .isEqualTo(Reason.SIGNATURE);
    }

    @Test
    void theClaimedIdsForTheLogAreReadOnlyWhenWellFormed() {
        TestApprovalSigner.Token token = token();
        assertThat(ApprovalToken.Claimed.peek(token.sign()))
                .isEqualTo(new ApprovalToken.Claimed(token.id(), "PLACE_ORDER"));
        assertThat(ApprovalToken.Claimed.peek(token().claims(c -> c.jwtID("x\nFAKE LOG LINE")
                .claim("act", "A\nB")).sign())).isEqualTo(ApprovalToken.Claimed.NONE);
        assertThat(ApprovalToken.Claimed.peek("garbage")).isEqualTo(ApprovalToken.Claimed.NONE);
        assertThat(ApprovalToken.Claimed.peek(null)).isEqualTo(ApprovalToken.Claimed.NONE);
    }

    @Test
    void anIssuerAndAnAudienceAreRequired() {
        assertThatThrownBy(() -> new ApprovalTokenVerifier(new ImmutableJWKSet<>(new JWKSet()), "x", " ",
                Duration.ZERO, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }
}
