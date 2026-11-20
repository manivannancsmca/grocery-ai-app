package com.grocery.common.approval;

import com.grocery.common.approval.ApprovalTokenException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The orchestrator's JWKS over HTTP: caching, refetch on an unknown key id, rate limit, outage. */
class ApprovalRemoteKeysTest {

    private static final UUID APPROVER = UUID.randomUUID();

    private final TestApprovalSigner current = TestApprovalSigner.generate("key-1");
    private final TestApprovalSigner next = TestApprovalSigner.generate("key-2");
    private final TestApprovalSigner unknown = TestApprovalSigner.generate("key-x");
    private final StubJwks jwks = StubJwks.start(current.publicKey());

    @AfterEach
    void stop() {
        jwks.close();
    }

    private ApprovalTokenVerifier verifier(String uri, Duration minRefetchInterval) throws Exception {
        return new ApprovalTokenVerifier(ApprovalTokenVerifier.remoteKeys(new URI(uri).toURL(), Duration.ofSeconds(2),
                Duration.ofMinutes(5), minRefetchInterval), ApprovalToken.DEFAULT_ISSUER, TestApprovalSigner.AUDIENCE,
                Duration.ofSeconds(5), Clock.systemUTC());
    }

    private static ApprovalTokenException refused(ApprovalTokenVerifier verifier, TestApprovalSigner signer) {
        try {
            verify(verifier, signer);
        } catch (ApprovalTokenException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("accepted");
    }

    private static ApprovalContext verify(ApprovalTokenVerifier verifier, TestApprovalSigner signer) throws Exception {
        return verifier.verify(signer.token(APPROVER).sign(), "POST", "/api/orders", () -> new byte[0],
                APPROVER.toString());
    }

    @Test
    void theKeySetIsFetchedLazilyAndCached() throws Exception {
        try (ApprovalTokenVerifier verifier = verifier(jwks.uri(), Duration.ofSeconds(30))) {
            assertThat(jwks.fetches()).isZero();
            for (int i = 0; i < 3; i++) {
                assertThat(verify(verifier, current)).isNotNull();
            }
            assertThat(jwks.fetches()).isEqualTo(1);
        }
    }

    @Test
    void anUnknownKeyIdRefetchesOnceSoARotationIsPickedUpAtOnce() throws Exception {
        try (ApprovalTokenVerifier verifier = verifier(jwks.uri(), Duration.ZERO)) {
            verify(verifier, current);
            assertThat(jwks.fetches()).isEqualTo(1);

            assertThat(refused(verifier, unknown).reason()).isEqualTo(Reason.SIGNATURE);
            assertThat(jwks.fetches()).isEqualTo(2);

            jwks.keys(current.publicKey(), next.publicKey());          // the orchestrator rotated its key
            assertThat(verify(verifier, next)).isNotNull();
            assertThat(jwks.fetches()).isEqualTo(3);
            assertThat(verify(verifier, current)).isNotNull();
            assertThat(jwks.fetches()).isEqualTo(3);
        }
    }

    @Test
    void refetchesForUnknownKeyIdsAreRateLimitedAndThenRefusedAsSignature() throws Exception {
        try (ApprovalTokenVerifier verifier = verifier(jwks.uri(), Duration.ofSeconds(30))) {
            verify(verifier, current);
            assertThat(refused(verifier, unknown).reason()).isEqualTo(Reason.SIGNATURE);
            int fetches = jwks.fetches();
            for (int i = 0; i < 5; i++) {
                assertThat(refused(verifier, unknown).reason()).isEqualTo(Reason.SIGNATURE);
            }
            assertThat(jwks.fetches()).isEqualTo(fetches);             // no flood reaches the orchestrator
            assertThat(verify(verifier, current)).isNotNull();          // known keys keep working
        }
    }

    @Test
    void keysThatCannotBeLoadedAre503() throws Exception {
        jwks.down(true);
        try (ApprovalTokenVerifier verifier = verifier(jwks.uri(), Duration.ofSeconds(30))) {
            ApprovalTokenException e = refused(verifier, current);
            assertThat(e.errorCode()).isEqualTo("APPROVAL_KEYS_UNAVAILABLE");
            assertThat(e.status().value()).isEqualTo(503);
        }
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (ApprovalTokenVerifier verifier = verifier("http://127.0.0.1:" + closedPort + StubJwks.PATH,
                Duration.ofSeconds(30))) {
            assertThat(refused(verifier, current).errorCode()).isEqualTo("APPROVAL_KEYS_UNAVAILABLE");
        }
    }

    @Test
    void theRefetchLimitMustBeShorterThanTheCache() throws Exception {
        URL url = new URI(jwks.uri()).toURL();
        assertThatThrownBy(() -> ApprovalTokenVerifier.remoteKeys(url, Duration.ofSeconds(2), Duration.ofSeconds(30),
                Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwks-min-refetch-interval");
        assertThatThrownBy(() -> ApprovalTokenVerifier.remoteKeys(url, Duration.ofSeconds(2), Duration.ZERO,
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
