package com.grocery.common.approval;

import com.grocery.common.correlation.CorrelationId;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The filter around the verifier: when it acts, what the handler receives, how refusals are answered. */
class ApprovalTokenFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BODY = "{\"amount\":125.00,\"note\":\"damaged é\"}";
    private static final UUID APPROVER = UUID.randomUUID();

    private final TestApprovalSigner signer = TestApprovalSigner.generate("k1");
    private final List<ApprovalContext> consumed = new ArrayList<>();
    private volatile SQLException databaseFailure;

    private final ConsumedApprovals store = new ConsumedApprovals((DataSource) null, Clock.systemUTC()) {
        @Override
        public boolean consume(ApprovalContext approval) throws SQLException {
            if (databaseFailure != null) {
                throw databaseFailure;
            }
            if (consumed.stream().anyMatch(c -> c.approvalId().equals(approval.approvalId()))) {
                return false;
            }
            consumed.add(approval);
            return true;
        }
    };

    private final ApprovalTokenFilter filter = new ApprovalTokenFilter(
            new ApprovalTokenVerifier(new ImmutableJWKSet<>(new JWKSet(signer.publicKey())), ApprovalToken.DEFAULT_ISSUER,
                    TestApprovalSigner.AUDIENCE, Duration.ofSeconds(5), Clock.systemUTC()),
            store, JSON);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private static void authenticatedAs(UUID user) {
        Jwt jwt = Jwt.withTokenValue("access-token").header("alg", "RS256").subject(user.toString())
                .claim("roles", List.of("FINANCE_ADMIN")).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"))));
    }

    private static MockHttpServletRequest request(String body, String... tokens) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/refunds");
        request.setContentType("application/json");
        request.setCharacterEncoding("UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        for (String token : tokens) {
            request.addHeader(ApprovalToken.HEADER, token);
        }
        return request;
    }

    private TestApprovalSigner.Token refund() {
        return signer.token(APPROVER).onBehalfOf(null).action("REFUND_PAYMENT")
                .request("POST", "/api/admin/refunds").body(BODY);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> problem(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentType()).startsWith("application/problem+json");
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    @Test
    void withoutTheHeaderTheRequestPassesUntouched() throws Exception {
        MockHttpServletRequest request = request(BODY);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(ApprovalContext.ATTRIBUTE)).isNull();
        assertThat(consumed).isEmpty();
    }

    @Test
    void aVerifiedApprovalIsConsumedAndTheHandlerGetsTheSameBodyAndTheContext() throws Exception {
        authenticatedAs(APPROVER);
        TestApprovalSigner.Token token = refund();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(BODY, token.sign()), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        ServletRequest handled = chain.getRequest();
        assertThat(handled).isNotNull();
        ApprovalContext expected = new ApprovalContext(token.id(), "REFUND_PAYMENT", APPROVER, null);
        assertThat(ApprovalContext.from(handled)).contains(expected);
        assertThat(consumed).containsExactly(expected);
        assertThat(handled.getInputStream().readAllBytes()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(((HttpServletRequest) handled).getReader().readLine()).isEqualTo(BODY);
        assertThat(handled.getContentLength()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aSecondUseIs409() throws Exception {
        authenticatedAs(APPROVER);
        String token = refund().sign();
        filter.doFilter(request(BODY, token), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MDC.put(CorrelationId.MDC_KEY, "corr-approval-0001");
        filter.doFilter(request(BODY, token), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(problem(response)).containsEntry("errorCode", "APPROVAL_ALREADY_USED").containsEntry("status", 409)
                .containsEntry("title", "Conflict").containsEntry("correlationId", "corr-approval-0001")
                .doesNotContainKey("reason");
    }

    @Test
    void refusalsAreProblemDetailsAndTheHandlerNeverRuns() throws Exception {
        authenticatedAs(APPROVER);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(BODY, refund().audiences("payment-service").sign()), response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(problem(response)).containsEntry("errorCode", "APPROVAL_TOKEN_INVALID")
                .containsEntry("reason", "AUDIENCE").containsEntry("type", "about:blank")
                .containsEntry("detail", "The approval token is not meant for this service.");

        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        filter.doFilter(request(BODY.replace("125.00", "1250.00"), refund().sign()), response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(problem(response)).containsEntry("errorCode", "APPROVAL_BODY_MISMATCH").doesNotContainKey("reason");
        assertThat(consumed).isEmpty();
    }

    @Test
    void severalHeadersAnEmptyHeaderOrNoAuthenticatedUserAreRefused() throws Exception {
        String token = refund().sign();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(BODY, token), response, new MockFilterChain());
        assertThat(problem(response)).containsEntry("reason", "SUBJECT");          // nobody is authenticated

        authenticatedAs(APPROVER);
        response = new MockHttpServletResponse();
        filter.doFilter(request(BODY, token, token), response, new MockFilterChain());
        assertThat(problem(response)).containsEntry("reason", "SIGNATURE");

        response = new MockHttpServletResponse();
        filter.doFilter(request(BODY, ""), response, new MockFilterChain());
        assertThat(problem(response)).containsEntry("reason", "SIGNATURE");
        assertThat(consumed).isEmpty();
    }

    @Test
    void anOversizedBodyCannotBeTheApprovedOne() throws Exception {
        authenticatedAs(APPROVER);
        String large = "{\"note\":\"" + "x".repeat(ApprovalTokenFilter.MAX_BODY_BYTES) + "\"}";
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(large, refund().body(large).sign()), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(problem(response)).containsEntry("errorCode", "APPROVAL_BODY_MISMATCH");
    }

    @Test
    void whenTheApprovalCannotBeRecordedNothingRuns() throws Exception {
        authenticatedAs(APPROVER);
        databaseFailure = new SQLException("Communications link failure", "08S01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(BODY, refund().sign()), response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(problem(response)).containsEntry("errorCode", "INTERNAL_ERROR");
    }

    @Test
    void theTargetIsThePathAsReceivedPlusAnyQuery() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/0190%2Fx/cancel");
        assertThat(ApprovalTokenFilter.target(request)).isEqualTo("/api/orders/0190%2Fx/cancel");
        request.setQueryString("force=true");
        assertThat(ApprovalTokenFilter.target(request)).isEqualTo("/api/orders/0190%2Fx/cancel?force=true");
        request.setQueryString("");
        assertThat(ApprovalTokenFilter.target(request)).isEqualTo("/api/orders/0190%2Fx/cancel");
    }
}
