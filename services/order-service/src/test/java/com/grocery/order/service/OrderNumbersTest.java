package com.grocery.order.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Order numbers: "GA-" + 8 Crockford base32 characters (no I, L, O, U) from 40 random bits. */
class OrderNumbersTest {

    @Test
    void formatsFortyBitsAsEightCrockfordCharacters() {
        assertThat(OrderNumbers.format(0)).isEqualTo("GA-00000000");
        assertThat(OrderNumbers.format(31)).isEqualTo("GA-0000000Z");
        assertThat(OrderNumbers.format(32)).isEqualTo("GA-00000010");
        assertThat(OrderNumbers.format(0xFF_FFFF_FFFFL)).isEqualTo("GA-ZZZZZZZZ");
        assertThat(OrderNumbers.format(0x1_00_0000_0000L)).isEqualTo("GA-00000000");     // only the low 40 bits
    }

    @Test
    void randomNumbersAreReadableAndRarelyRepeat() {
        OrderNumbers numbers = new OrderNumbers();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            String number = numbers.next();
            assertThat(number).matches("GA-[0-9A-HJKMNP-TV-Z]{8}").hasSize(11);
            seen.add(number);
        }
        assertThat(seen).hasSizeGreaterThan(19_990);                 // 2^40 values: a collision is very rare
    }

    @Test
    void returnNumbersUseTheirOwnPrefix() {
        assertThat(OrderNumbers.format(OrderNumbers.RETURN_PREFIX, 31)).isEqualTo("RT-0000000Z");
        assertThat(new OrderNumbers().nextReturn()).matches("RT-[0-9A-HJKMNP-TV-Z]{8}").hasSize(11);
    }

    @Test
    void complaintNumbersUseTheirOwnPrefix() {
        assertThat(OrderNumbers.format(OrderNumbers.COMPLAINT_PREFIX, 31)).isEqualTo("CMP-0000000Z");
        assertThat(new OrderNumbers().nextComplaint()).matches("CMP-[0-9A-HJKMNP-TV-Z]{8}").hasSize(12);
    }
}
