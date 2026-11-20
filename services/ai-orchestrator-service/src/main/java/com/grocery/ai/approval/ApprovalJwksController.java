package com.grocery.ai.approval;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * The public keys of approval tokens, for the target services' filter (grocery-common 0.8.0, {@code APPROVAL_JWKS_URI}).
 * Unauthenticated (it is in {@code grocery.security.public-paths}) and internal: the gateway never routes it.
 */
@RestController
public class ApprovalJwksController {

    public static final String PATH = "/.well-known/approval-jwks.json";

    private final ApprovalSigningKey signingKey;

    public ApprovalJwksController(ApprovalSigningKey signingKey) {
        this.signingKey = signingKey;
    }

    @GetMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                .body(signingKey.publicJwks().toJSONObject());
    }
}
