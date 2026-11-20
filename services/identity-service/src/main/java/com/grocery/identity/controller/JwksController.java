package com.grocery.identity.controller;

import com.grocery.identity.security.SigningKeyService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** Public keys that every service (and the gateway) uses to verify access tokens. */
@RestController
public class JwksController {

    private final SigningKeyService signingKeyService;

    public JwksController(SigningKeyService signingKeyService) {
        this.signingKeyService = signingKeyService;
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(signingKeyService.publicJwkSet().toJSONObject(true));
    }
}
