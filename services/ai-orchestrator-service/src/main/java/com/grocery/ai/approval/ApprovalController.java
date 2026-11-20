package com.grocery.ai.approval;

import com.grocery.ai.gateway.CallContext;
import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Human decisions (contract §4). There is no approve tool: a decision is only ever this REST call with a person's JWT.
 */
@RestController
@RequestMapping("/api/approvals")
@PreAuthorize("isAuthenticated()")
public class ApprovalController {

    private final ApprovalService approvals;
    private final Exporter exporter;

    public ApprovalController(ApprovalService approvals, Exporter exporter) {
        this.approvals = approvals;
        this.exporter = exporter;
    }

    public record ApproveRequest(@Size(max = 300) String comment) {
    }

    public record RejectRequest(@NotBlank @Size(max = 300) String comment) {
    }

    /** Newest first; {@code scope=mine} (default) or {@code to-decide}. */
    @GetMapping
    public PageResponse<ApprovalView> list(@RequestParam(required = false) String scope,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           CurrentUser user) {
        return approvals.list(user, scope, status, page, size);
    }

    /**
     * The list as a file ({@code format} = csv | xlsx | pdf): exactly the requests the caller's list shows (the same
     * {@code scope} and {@code status} rules), every page, newest first or by {@code sort} ({@code requested} |
     * {@code request} | {@code requester}, then {@code ,asc} | {@code ,desc}).
     */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String scope,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, ApprovalExports.APPROVALS,
                approvals.listExport(user, scope, status, sort), Exporter.filters("Scope", scope, "Status", status));
    }

    @GetMapping("/{id}")
    public ApprovalView get(@PathVariable UUID id, CurrentUser user) {
        return approvals.get(user, id);
    }

    /** Executes synchronously; the answer is COMPLETED with {@code result} or FAILED with {@code failure}. */
    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest request,
                                CurrentUser user) {
        String comment = request == null || request.comment() == null || request.comment().isBlank() ? null
                : request.comment().strip();
        return approvals.approve(user, CallContext.current(), id, comment);
    }

    @PostMapping("/{id}/reject")
    public ApprovalView reject(@PathVariable UUID id, @Valid @RequestBody(required = false) RejectRequest request,
                               CurrentUser user) {
        if (request == null || request.comment() == null || request.comment().isBlank()) {
            throw ApprovalException.validation("comment", "must not be blank");
        }
        return approvals.reject(user, id, request.comment().strip());
    }

    @PostMapping("/{id}/cancel")
    public ApprovalView cancel(@PathVariable UUID id, CurrentUser user) {
        return approvals.cancel(user, id);
    }
}
