package com.grocery.payment.repository;

import com.grocery.payment.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByRazorpayPaymentId(String razorpayPaymentId);

    /** Oldest first (the order shown in {@code PaymentView.attempts}). */
    List<PaymentAttempt> findByPaymentIdOrderByCreatedAtAscIdAsc(UUID paymentId);
}
