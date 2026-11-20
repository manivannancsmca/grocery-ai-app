package com.grocery.common.approval;

import jakarta.servlet.ServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * The verified, now consumed approval of the current request: set as the request attribute {@link #ATTRIBUTE} by
 * {@link ApprovalTokenFilter} before the handler runs, absent for ordinary requests (no {@code X-Approval-Token}).
 * Services may record it for audit:
 * <pre>{@code ApprovalContext.current().ifPresent(a -> order.setApprovalId(a.approvalId())); }</pre>
 *
 * @param approvalId the approval id ({@code jti})
 * @param action     the approved action ({@code act}), e.g. {@code PLACE_ORDER}
 * @param approverId the human who approved it ({@code sub}, equal to the request JWT's subject)
 * @param onBehalfOf the customer the action is for ({@code obo}); {@code null} for admin actions
 */
public record ApprovalContext(UUID approvalId, String action, UUID approverId, UUID onBehalfOf) {

    /** Request attribute name. */
    public static final String ATTRIBUTE = ApprovalContext.class.getName();

    /** The approval of {@code request}, if it carried a verified approval token. */
    public static Optional<ApprovalContext> from(ServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof ApprovalContext approval
                ? Optional.of(approval) : Optional.empty();
    }

    /** The approval of the request bound to the current thread (Spring MVC handlers), if any. */
    public static Optional<ApprovalContext> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof ApprovalContext approval
                ? Optional.of(approval) : Optional.empty();
    }
}
