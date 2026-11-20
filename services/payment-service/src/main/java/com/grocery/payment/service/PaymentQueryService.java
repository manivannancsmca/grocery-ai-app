package com.grocery.payment.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.payment.dto.PaymentDtos.PaymentView;
import com.grocery.payment.dto.PaymentDtos.RefundView;
import com.grocery.payment.dto.PaymentExports;
import com.grocery.payment.dto.PaymentMapper;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.entity.Refund;
import com.grocery.payment.entity.RefundSummary;
import com.grocery.payment.repository.PaymentAttemptRepository;
import com.grocery.payment.repository.PaymentRepository;
import com.grocery.payment.repository.RefundRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Reads: the customer's payment, the admin payment monitor and refund views. No locks (plain consistent reads). */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    /** Admin {@code from} / {@code to} dates are calendar days in India time, like the admin order list. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final RefundRepository refunds;
    private final ExportQueries exports;

    public PaymentQueryService(PaymentRepository payments, PaymentAttemptRepository attempts,
                               RefundRepository refunds, ExportQueries exports) {
        this.payments = payments;
        this.attempts = attempts;
        this.refunds = refunds;
        this.exports = exports;
    }

    /** The full view (attempts and refunds) of an existing payment. */
    public PaymentView view(UUID paymentId) {
        return payments.findById(paymentId).map(this::full).orElseThrow(PaymentException::notFound);
    }

    /** {@code GET /api/payments/orders/{orderId}}: the customer's own payment; any admin may read any. */
    public PaymentView forOrder(UUID orderId, CurrentUser user) {
        Payment payment = payments.findByOrderId(orderId)
                .filter(p -> user.isAdmin() || p.getCustomerId().equals(user.userId()))
                .orElseThrow(PaymentException::notFound);
        return full(payment);
    }

    public RefundView refund(UUID refundId) {
        return refunds.findById(refundId).map(PaymentMapper::refund)
                .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " not found"));
    }

    /**
     * {@code GET /api/admin/payments}: newest first, without attempts. {@code from} / {@code to} = creation dates in
     * India time, inclusive.
     */
    public PageResponse<PaymentView> adminList(PaymentStatus status, RefundSummary refundStatus, UUID orderId,
                                               LocalDate from, LocalDate to, Integer page, Integer size) {
        Page<Payment> result = payments.findAll(adminFilter(status, refundStatus, orderId, from, to),
                Pages.request(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Map<UUID, List<Refund>> refundsByPayment = refundsOf(result.getContent());
        return Pages.toResponse(result,
                p -> PaymentMapper.view(p, null, refundsByPayment.getOrDefault(p.getId(), List.of())));
    }

    /**
     * {@code GET /api/admin/payments/export}: the admin list's filters on every page, newest first or by
     * {@code sort}, in keyset batches; each batch's refunds are looked up at once, as the list does per page. Not in
     * this class's transaction: each batch has its own.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RowSource<PaymentView> adminExport(PaymentStatus status, RefundSummary refundStatus, UUID orderId,
                                              LocalDate from, LocalDate to, String sort) {
        return exports.specification(payments, adminFilter(status, refundStatus, orderId, from, to))
                .orderBy(PaymentExports.ADMIN_SORT.resolve(sort))
                .batches(batch -> {
                    Map<UUID, List<Refund>> refundsByPayment = refundsOf(batch);
                    return batch.stream()
                            .map(p -> PaymentMapper.view(p, null, refundsByPayment.getOrDefault(p.getId(), List.of())))
                            .toList();
                });
    }

    /** The admin list's filters (shared by the list and its export). */
    private static Specification<Payment> adminFilter(PaymentStatus status, RefundSummary refundStatus, UUID orderId,
                                                      LocalDate from, LocalDate to) {
        Instant createdFrom = from == null ? null : from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant createdUntil = to == null ? null : to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (refundStatus != null) {
                predicates.add(cb.equal(root.get("refundStatus"), refundStatus));
            }
            if (orderId != null) {
                predicates.add(cb.equal(root.get("orderId"), orderId));
            }
            if (createdFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            }
            if (createdUntil != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), createdUntil));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** The refunds of a page of payments (one query), oldest first per payment. */
    private Map<UUID, List<Refund>> refundsOf(List<Payment> page) {
        return page.isEmpty() ? Map.of()
                : refunds.findByPaymentIdIn(page.stream().map(Payment::getId).toList()).stream()
                .sorted(Comparator.comparing(Refund::getCreatedAt).thenComparing(Refund::getId))
                .collect(Collectors.groupingBy(Refund::getPaymentId));
    }

    private PaymentView full(Payment payment) {
        return PaymentMapper.view(payment, attempts.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()),
                refunds.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()));
    }
}
