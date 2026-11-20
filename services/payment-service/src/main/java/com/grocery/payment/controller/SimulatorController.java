package com.grocery.payment.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.payment.dto.PaymentDtos.SimulatedPayRequest;
import com.grocery.payment.dto.PaymentDtos.SimulatedPayment;
import com.grocery.payment.service.SimulatorService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The local payment simulator (contract §3.3); 404 in Razorpay mode. */
@RestController
public class SimulatorController {

    private final SimulatorService simulator;

    public SimulatorController(SimulatorService simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/api/payments/simulator/orders/{orderId}/pay")
    @PreAuthorize("hasRole('CUSTOMER')")
    public SimulatedPayment pay(@PathVariable UUID orderId, @Valid @RequestBody SimulatedPayRequest request,
                                CurrentUser user) {
        return simulator.pay(orderId, request.outcome(), user);
    }
}
