package com.grocery.common.approval;

import com.grocery.common.correlation.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies {@code X-Approval-Token} in a target service (contract Phase 11 §5). Registered as a servlet filter that
 * runs <em>after</em> the Spring Security filter chain (so the caller is authenticated and authorised as usual) and
 * before the {@code DispatcherServlet}.
 * <ul>
 *   <li>No header: the request is untouched (an ordinary user request).</li>
 *   <li>With the header: {@link ApprovalTokenVerifier} checks signature, type, issuer, audience, lifetime, method and
 *       path, body hash and subject; then {@link ConsumedApprovals} records the approval id in its own transaction.
 *       Only then does the handler run, with the same body bytes and the {@link ApprovalContext} request
 *       attribute.</li>
 *   <li>Refused: a ProblemDetail ({@code errorCode}, {@code reason} for {@code APPROVAL_TOKEN_INVALID}); the handler
 *       never runs.</li>
 * </ul>
 * Every accepted and refused token is logged (approval id, action, outcome, request), never the token itself.
 */
public class ApprovalTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTokenFilter.class);

    /** Approved request bodies are small; anything larger cannot be one. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private final ApprovalTokenVerifier verifier;
    private final ConsumedApprovals consumedApprovals;
    private final JsonMapper jsonMapper;

    public ApprovalTokenFilter(ApprovalTokenVerifier verifier, ConsumedApprovals consumedApprovals,
                               JsonMapper jsonMapper) {
        this.verifier = verifier;
        this.consumedApprovals = consumedApprovals;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(ApprovalToken.HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyRequest cached = new CachedBodyRequest(request, MAX_BODY_BYTES);
        List<String> tokens = Collections.list(request.getHeaders(ApprovalToken.HEADER));
        String token = tokens.isEmpty() ? null : tokens.getFirst();
        String method = request.getMethod();
        String target = target(request);
        ApprovalContext approval;
        try {
            if (tokens.size() != 1) {
                throw ApprovalTokenException.invalid(ApprovalTokenException.Reason.SIGNATURE,
                        tokens.size() + " " + ApprovalToken.HEADER + " headers");
            }
            approval = verifier.verify(token, method, target, () -> body(cached), authenticatedSubject());
            if (!consumedApprovals.consume(approval)) {
                throw ApprovalTokenException.alreadyUsed();
            }
        } catch (ApprovalTokenException e) {
            ApprovalToken.Claimed claimed = ApprovalToken.Claimed.peek(token);
            String outcome = e.reason() == null ? e.errorCode() : e.errorCode() + "/" + e.reason();
            if (e.status() == HttpStatus.SERVICE_UNAVAILABLE) {
                log.error("Approval token refused: {} approval={} action={} request={} {} ({})", outcome,
                        claimed.approvalId(), claimed.action(), method, target, e.logDetail());
            } else {
                log.warn("Approval token refused: {} approval={} action={} request={} {} ({})", outcome,
                        claimed.approvalId(), claimed.action(), method, target, e.logDetail());
            }
            write(response, e.status(), e.getMessage(), e.errorCode(), e.properties());
            return;
        } catch (SQLException | RuntimeException e) {
            ApprovalToken.Claimed claimed = ApprovalToken.Claimed.peek(token);
            log.error("Approval token not processed (nothing executed): approval={} action={} request={} {}",
                    claimed.approvalId(), claimed.action(), method, target, e);
            write(response, HttpStatus.INTERNAL_SERVER_ERROR,
                    "An unexpected error occurred. Please try again later.", "INTERNAL_ERROR", Map.of());
            return;
        }
        log.info("Approval token accepted: approval={} action={} approver={} onBehalfOf={} request={} {}",
                approval.approvalId(), approval.action(), approval.approverId(), approval.onBehalfOf(), method, target);
        cached.setAttribute(ApprovalContext.ATTRIBUTE, approval);
        chain.doFilter(cached, response);
    }

    private static byte[] body(CachedBodyRequest request) throws IOException {
        try {
            return request.body();
        } catch (CachedBodyRequest.BodyTooLargeException e) {
            throw ApprovalTokenException.bodyMismatch(e.getMessage());
        }
    }

    /** The path as this service received it (undecoded), plus {@code ?query} when the request has one. */
    static String target(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isEmpty() ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    /** The authenticated JWT's {@code sub} (the name of another authentication), or {@code null}. */
    static String authenticatedSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : authentication.getName();
    }

    private void write(HttpServletResponse response, HttpStatus status, String detail, String errorCode,
                       Map<String, Object> properties) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("errorCode", errorCode);
        body.putAll(properties);
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }
}
