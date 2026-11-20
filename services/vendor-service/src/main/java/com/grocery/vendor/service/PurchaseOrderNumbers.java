package com.grocery.vendor.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Purchase order numbers {@code PO-XXXXXXXX} (Phase 12 §4): 8 characters of Crockford base32 (no I, L, O, U — easy to
 * read out and type) from 40 random bits, about 10^12 values, as order-service's {@code GA-} numbers. Uniqueness is
 * guaranteed by {@code uk_purchase_order_number}; the creating service skips a number that is already taken.
 */
@Component
public class PurchaseOrderNumbers {

    public static final String PREFIX = "PO-";
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int LENGTH = 8;
    /** {@code ^PO-[0-9A-HJKMNP-TV-Z]{8}$} */
    public static final String PATTERN = "^PO-[0-9A-HJKMNP-TV-Z]{8}$";

    private final SecureRandom random = new SecureRandom();

    public String next() {
        return format(random.nextLong() & 0xFF_FFFF_FFFFL);
    }

    /** {@code bits}' lowest 40 bits as {@code PO-XXXXXXXX}. */
    static String format(long bits) {
        char[] chars = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            chars[i] = ALPHABET.charAt((int) (bits & 31));
            bits >>>= 5;
        }
        return PREFIX + new String(chars);
    }
}
