package com.grocery.order.service;

import com.grocery.order.service.OrderPlacementService.Fingerprint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Idempotency-Key's request hash is the SHA-256 of the fingerprint's JSON. Phase 11 adds
 * {@code expectedCartUpdatedAt}: a body without it must serialise exactly as before, so keys stored before the upgrade
 * still replay.
 */
class PlaceOrderFingerprintTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aBodyWithoutTheCartExpectationHashesAsBefore() {
        UUID address = UUID.fromString("0197a3c1-7f2e-7c1d-9a4b-1f2e3d4c5b6a");
        assertThat(json.writeValueAsString(new Fingerprint(address, new BigDecimal("245.00"), null, null)))
                .isEqualTo("{\"addressId\":\"" + address + "\",\"expectedTotal\":245.00,\"note\":null}");
    }

    @Test
    void theCartExpectationIsPartOfTheBody() {
        assertThat(json.writeValueAsString(new Fingerprint(null, new BigDecimal("245.00"), "Ring twice",
                Instant.parse("2026-11-11T10:00:00.123Z"))))
                .isEqualTo("{\"addressId\":null,\"expectedTotal\":245.00,\"note\":\"Ring twice\","
                        + "\"expectedCartUpdatedAt\":\"2026-11-11T10:00:00.123Z\"}");
    }
}
