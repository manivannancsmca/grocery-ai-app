package com.grocery.payment.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.payment.dto.PaymentDtos.CheckoutSession;
import com.grocery.payment.dto.PaymentDtos.PaymentView;
import com.grocery.payment.dto.PaymentDtos.VerifyRequest;
import com.grocery.payment.service.CheckoutService;
import com.grocery.payment.service.PaymentQueryService;
import com.grocery.payment.service.VerifyService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The customer's payment (contract §3): checkout session, verify, read. */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final CheckoutService checkout;
    private final VerifyService verify;
    private final PaymentQueryService queries;

    public PaymentController(CheckoutService checkout, VerifyService verify, PaymentQueryService queries) {
        this.checkout = checkout;
        this.verify = verify;
        this.queries = queries;
    }

    @GetMapping("/orders/{orderId}/checkout")
    @PreAuthorize("hasRole('CUSTOMER')")
    public CheckoutSession checkout(@PathVariable UUID orderId, CurrentUser user) {
        return checkout.session(orderId, user);
    }

    @PostMapping("/razorpay/verify")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PaymentView verify(@Valid @RequestBody VerifyRequest request, CurrentUser user) {
        return verify.verify(request, user);
    }

    /** The customer's own payment; any admin may read any. */
    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER') or " + Roles.ANY_ADMIN)
    public PaymentView payment(@PathVariable UUID orderId, CurrentUser user) {
        return queries.forOrder(orderId, user);
    }
}
