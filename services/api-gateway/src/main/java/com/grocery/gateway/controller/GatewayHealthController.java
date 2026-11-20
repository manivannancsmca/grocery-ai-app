package com.grocery.gateway.controller;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /api/health/gateway} — whether the gateway itself takes traffic (other {@code /api/health/*} paths are
 * routed to each service's actuator health). Used by the admin System Status page and, because the gateway's actuator
 * listens only on its internal management port, as the load balancer's readiness check on the public port:
 * {@code 200 UP} while it accepts traffic, {@code 503 OUT_OF_SERVICE} while it refuses it (start-up, graceful shutdown).
 */
@RestController
public class GatewayHealthController {

    private final ApplicationAvailability availability;

    public GatewayHealthController(ApplicationAvailability availability) {
        this.availability = availability;
    }

    @GetMapping("/api/health/gateway")
    public ResponseEntity<Map<String, String>> health() {
        if (availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC) {
            return ResponseEntity.ok(Map.of("status", "UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "OUT_OF_SERVICE"));
    }
}
