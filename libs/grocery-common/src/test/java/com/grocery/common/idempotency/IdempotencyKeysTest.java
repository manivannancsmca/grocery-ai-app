package com.grocery.common.idempotency;

import com.grocery.common.web.BadRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {

    private final IdempotencyKeys keys = new IdempotencyKeys(null, JsonMapper.builder().build(), Clock.systemUTC());

    @Test
    void keysMustBePresentAndWellFormed() {
        assertThat(IdempotencyKeys.requireKey(" 0197a3c1-7f2e-7c1d-9a4b-1f2e3d4c5b6a "))
                .isEqualTo("0197a3c1-7f2e-7c1d-9a4b-1f2e3d4c5b6a");
        assertThat(IdempotencyKeys.requireKey("checkout:1731318000")).isEqualTo("checkout:1731318000");

        assertThatThrownBy(() -> IdempotencyKeys.requireKey(null))
                .isInstanceOf(BadRequestException.class)
                .satisfies(e -> assertThat(((BadRequestException) e).errorCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
        assertThatThrownBy(() -> IdempotencyKeys.requireKey("  "))
                .satisfies(e -> assertThat(((BadRequestException) e).errorCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
        for (String bad : new String[]{"short", "has space in it", "x".repeat(101), "emoji-é-key"}) {
            assertThatThrownBy(() -> IdempotencyKeys.requireKey(bad))
                    .satisfies(e -> assertThat(((BadRequestException) e).errorCode()).isEqualTo("IDEMPOTENCY_KEY_INVALID"));
        }
    }

    @Test
    void equalRequestsHashEquallyAndDifferentOnesDiffer() {
        record Request(String addressId, String expectedTotal) {
        }
        String a = keys.hash(new Request("a1", "285.00"));
        assertThat(a).hasSize(64).isEqualTo(keys.hash(new Request("a1", "285.00")));
        assertThat(a).isNotEqualTo(keys.hash(new Request("a1", "286.00")));
        assertThat(keys.hash(null)).hasSize(64);
        assertThat(keys.hash(Map.of("k", 1))).isEqualTo(keys.hash(Map.of("k", 1)));
    }

    @Test
    void claimOutsideATransactionIsAProgrammingError() {
        assertThatThrownBy(() -> keys.claim("s", "key-12345678", "h", "r"))
                .isInstanceOf(IllegalStateException.class);
    }
}
