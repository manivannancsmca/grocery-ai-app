package com.grocery.review.service;

/**
 * The public name of a reviewer (contract §1): first name and last initial, e.g. "Asha Sharma" → "Asha S.", taken
 * from the JWT {@code name} claim when the review is written. A single name stays as it is; no name gives
 * "Customer".
 */
public final class AuthorNames {

    static final String ANONYMOUS = "Customer";
    static final int MAX_FIRST_NAME = 60;

    private AuthorNames() {
    }

    public static String publicName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return ANONYMOUS;
        }
        String[] parts = fullName.strip().split("\\s+");
        String first = parts[0];
        if (first.codePointCount(0, first.length()) > MAX_FIRST_NAME) {
            first = first.substring(0, first.offsetByCodePoints(0, MAX_FIRST_NAME));
        }
        if (parts.length == 1) {
            return first;
        }
        String last = parts[parts.length - 1];
        int initial = Character.toUpperCase(last.codePointAt(0));
        return first + " " + new String(Character.toChars(initial)) + ".";
    }
}
