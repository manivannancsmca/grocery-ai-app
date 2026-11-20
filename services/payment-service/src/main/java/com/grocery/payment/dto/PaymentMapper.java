package com.grocery.payment.dto;

import com.grocery.payment.dto.PaymentDtos.AttemptView;
import com.grocery.payment.dto.PaymentDtos.PaymentView;
import com.grocery.payment.dto.PaymentDtos.RefundView;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentAttempt;
import com.grocery.payment.entity.Refund;

import java.util.List;

/** Entity → response mapping (no logic beyond shaping). */
public final class PaymentMapper {

    private PaymentMapper() {
    }

    /** @param attempts null = leave them out (admin list rows) */
    public static PaymentView view(Payment p, List<PaymentAttempt> attempts, List<Refund> refunds) {
        return new PaymentView(p.getId(), p.getOrderId(), p.getOrderNumber(), p.getCustomerId(), p.getStatus(),
                p.getAmount(), p.getCurrency(), p.getMethod(), p.getRazorpayOrderId(), p.getRazorpayPaymentId(),
                p.getRefundedAmount(), p.getRefundStatus(), p.getExpiresAt(), p.getCapturedAt(), p.getCreatedAt(),
                attempts == null ? null : attempts.stream().map(PaymentMapper::attempt).toList(),
                refunds.stream().map(PaymentMapper::refund).toList());
    }

    public static AttemptView attempt(PaymentAttempt a) {
        return new AttemptView(a.getRazorpayPaymentId(), a.getStatus(), a.getMethod(), a.getErrorCode(),
                a.getErrorDescription(), a.getUpdatedAt());
    }

    public static RefundView refund(Refund r) {
        return new RefundView(r.getId(), r.getPaymentId(), r.getOrderId(), r.getAmount(), r.getReason(), r.getNote(),
                r.getStatus(), r.getRazorpayRefundId(), r.getFailureReason(), r.getRequestedBy(), r.getReturnId(),
                r.getCreatedAt(), r.getProcessedAt());
    }
}
