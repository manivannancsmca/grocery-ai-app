package com.grocery.payment.controller;

import com.grocery.payment.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay webhooks (contract §3.2). <b>Public</b> — no token ({@code grocery.security.public-paths}); authenticated
 * by the HMAC signature of the raw body instead. The body is bound as bytes, so the signature is checked over
 * exactly what Razorpay sent, before anything parses it.
 */
@RestController
public class RazorpayWebhookController {

    static final String SIGNATURE_HEADER = "X-Razorpay-Signature";
    static final String EVENT_ID_HEADER = "X-Razorpay-Event-Id";

    private final WebhookService webhooks;

    public RazorpayWebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/api/payments/razorpay/webhook")
    public ResponseEntity<Void> webhook(@RequestBody(required = false) byte[] body,
                                        @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                        @RequestHeader(value = EVENT_ID_HEADER, required = false) String eventId) {
        webhooks.receive(body == null ? new byte[0] : body, signature, eventId);
        return ResponseEntity.ok().build();
    }
}
