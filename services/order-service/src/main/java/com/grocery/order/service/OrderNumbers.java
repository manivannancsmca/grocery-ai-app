package com.grocery.order.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Short, human-friendly order numbers: {@code GA-} + 8 characters of Crockford base32 (no I, L, O, U, so they are
 * easy to read out and type) from 40 random bits — about 10^12 values. Uniqueness is guaranteed by the unique key
 * {@code uk_customer_order_number}; the rare collision is retried with a new number (see {@link OrderPlacementService}).
 * Phase 12: return numbers are made the same way with the prefix {@code RT-} (unique key {@code uk_order_return_number},
 * see {@link ReturnService}). Complaint numbers use the prefix {@code CMP-} (unique key {@code uk_complaint_number},
 * see {@link ComplaintService}).
 */
@Component
public class OrderNumbers {

    public static final String PREFIX = "GA-";
    public static final String RETURN_PREFIX = "RT-";
    public static final String COMPLAINT_PREFIX = "CMP-";
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        return format(random.nextLong() & 0xFF_FFFF_FFFFL);
    }

    /** A return number, {@code RT-XXXXXXXX}. */
    public String nextReturn() {
        return format(RETURN_PREFIX, random.nextLong() & 0xFF_FFFF_FFFFL);
    }

    /** A complaint number, {@code CMP-XXXXXXXX}. */
    public String nextComplaint() {
        return format(COMPLAINT_PREFIX, random.nextLong() & 0xFF_FFFF_FFFFL);
    }

    /** {@code bits}' lowest 40 bits as {@code GA-XXXXXXXX}. */
    static String format(long bits) {
        return format(PREFIX, bits);
    }

    static String format(String prefix, long bits) {
        char[] chars = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            chars[i] = ALPHABET.charAt((int) (bits & 31));
            bits >>>= 5;
        }
        return prefix + new String(chars);
    }
}
