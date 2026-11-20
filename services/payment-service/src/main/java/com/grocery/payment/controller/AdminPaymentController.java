package com.grocery.payment.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.idempotency.IdempotencyKeys;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import com.grocery.payment.dto.PaymentDtos.AdminRefundRequest;
import com.grocery.payment.dto.PaymentDtos.PaymentView;
import com.grocery.payment.dto.PaymentDtos.ReconciliationSummary;
import com.grocery.payment.dto.PaymentDtos.RefundView;
import com.grocery.payment.dto.PaymentExports;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.entity.RefundSummary;
import com.grocery.payment.service.AdminRefundService;
import com.grocery.payment.service.AdminRefundService.Requested;
import com.grocery.payment.service.PaymentQueryService;
import com.grocery.payment.service.PaymentReconciler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Payment monitor and refunds (contract §4). Reads: any admin role. Refunds: SUPER_ADMIN, FINANCE_ADMIN. Phase 10 §3:
 * the reconciliation summary, any admin role.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminPaymentController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    static final String REFUND_ADMIN = "hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN')";

    private final PaymentQueryService queries;
    private final AdminRefundService refunds;
    private final PaymentReconciler reconciler;
    private final Exporter exporter;

    public AdminPaymentController(PaymentQueryService queries, AdminRefundService refunds,
                                  PaymentReconciler reconciler, Exporter exporter) {
        this.queries = queries;
        this.refunds = refunds;
        this.reconciler = reconciler;
        this.exporter = exporter;
    }

    /** Newest first, rows without {@code attempts}; {@code from} / {@code to} = dates (India time), inclusive. */
    @GetMapping("/payments")
    @PreAuthorize(Roles.ANY_ADMIN)
    public PageResponse<PaymentView> payments(@RequestParam(required = false) PaymentStatus status,
                                              @RequestParam(required = false) RefundSummary refundStatus,
                                              @RequestParam(required = false) UUID orderId,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return queries.adminList(status, refundStatus, orderId, from, to, page, size);
    }

    /**
     * The monitor as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code order} | {@code amount} | {@code status} | {@code refunds}, then {@code ,asc} |
     * {@code ,desc}). Streamed in batches; the limits and errors are grocery-common's {@link Exporter}.
     */
    @GetMapping("/payments/export")
    @PreAuthorize(Roles.ANY_ADMIN)
    public void export(CurrentUser user,
                       @RequestParam(required = false) String format,
                       @RequestParam(required = false) PaymentStatus status,
                       @RequestParam(required = false) RefundSummary refundStatus,
                       @RequestParam(required = false) UUID orderId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String sort,
                       HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, PaymentExports.ADMIN_PAYMENTS,
                queries.adminExport(status, refundStatus, orderId, from, to, sort),
                Exporter.filters("Status", status, "Refund status", refundStatus, "Order ID", orderId, "From", from,
                        "To", to));
    }

    @GetMapping("/payments/{id}")
    @PreAuthorize(Roles.ANY_ADMIN)
    public PaymentView payment(@PathVariable UUID id) {
        return queries.view(id);
    }

    /** What payment reconciliation recovered: counters and the last 20 recoveries, newest first. */
    @GetMapping("/payments/reconciliation")
    @PreAuthorize(Roles.ANY_ADMIN)
    public ReconciliationSummary reconciliation() {
        return reconciler.summary();
    }

    /** 201 with the refund; a retry with the same key answers the original refund with {@code Idempotent-Replayed}. */
    @PostMapping("/refunds")
    @PreAuthorize(REFUND_ADMIN)
    public ResponseEntity<RefundView> refund(@RequestHeader(value = IdempotencyKeys.HEADER, required = false)
                                             String idempotencyKey,
                                             @RequestBody(required = false) AdminRefundRequest request,
                                             CurrentUser user) {
        Requested requested = refunds.refund(idempotencyKey, request, user);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (requested.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(requested.refund());
    }
}
