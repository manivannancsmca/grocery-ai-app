package com.grocery.payment.service;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.Refund;
import com.grocery.payment.event.PaymentEvents;
import org.springframework.stereotype.Component;

/**
 * Writes the payment events to the outbox (topic {@code payment.events}, aggregate {@code Payment}, key orderId)
 * in the caller's transaction — the one that made the change the event describes, while the payment row is locked.
 */
@Component
public class PaymentEventPublisher {

    private final OutboxPublisher outbox;

    public PaymentEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void initiated(Payment payment) {
        publish(payment, PaymentEvents.PAYMENT_INITIATED, new PaymentEvents.PaymentInitiated(payment.getOrderId(),
                payment.getId(), payment.getRazorpayOrderId(), payment.getAmount(), payment.getCurrency(),
                payment.getExpiresAt()));
    }

    public void captured(Payment payment) {
        publish(payment, PaymentEvents.PAYMENT_CAPTURED, new PaymentEvents.PaymentCaptured(payment.getOrderId(),
                payment.getId(), payment.getRazorpayPaymentId(), payment.getAmount(), payment.getMethod(),
                payment.getCapturedAt(), payment.isLateCapture()));
    }

    public void failed(Payment payment, String razorpayPaymentId, String errorCode, String errorDescription,
                       boolean retryable) {
        publish(payment, PaymentEvents.PAYMENT_FAILED, new PaymentEvents.PaymentFailed(payment.getOrderId(),
                payment.getId(), razorpayPaymentId, errorCode, errorDescription, retryable));
    }

    public void expired(Payment payment) {
        publish(payment, PaymentEvents.PAYMENT_EXPIRED,
                new PaymentEvents.PaymentExpired(payment.getOrderId(), payment.getId()));
    }

    /** After {@link Payment#refunded}: {@code totalRefunded} is the payment's new refunded amount. */
    public void refundCompleted(Payment payment, Refund refund) {
        publish(payment, PaymentEvents.REFUND_COMPLETED, new PaymentEvents.RefundCompleted(payment.getOrderId(),
                payment.getId(), refund.getId(), refund.getAmount(), refund.getReason().name(),
                payment.getRefundedAmount(), payment.isFullyRefunded(), refund.getReturnId()));
    }

    public void refundFailed(Payment payment, Refund refund) {
        publish(payment, PaymentEvents.REFUND_FAILED, new PaymentEvents.RefundFailed(payment.getOrderId(),
                payment.getId(), refund.getId(), refund.getAmount(), refund.getReason().name(),
                refund.getFailureCode(), refund.getReturnId()));
    }

    private void publish(Payment payment, String eventType, Object payload) {
        outbox.publish(OutboxEvent.of(PaymentEvents.TOPIC, PaymentEvents.AGGREGATE, payment.getOrderId().toString(),
                eventType, payload));
    }
}
