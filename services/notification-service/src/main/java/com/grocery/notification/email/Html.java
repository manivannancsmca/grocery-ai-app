package com.grocery.notification.email;

/**
 * HTML escaping for the email bodies. Every text that goes into the HTML part — titles, bodies, names, reasons, review
 * and return comments written by people — is escaped, in element content and in attribute values alike, so user text
 * can never add markup, scripts or links.
 */
public final class Html {

    private Html() {
    }

    /** {@code & < > " '} → entities; control characters other than tab / newline are dropped; null → "". */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> {
                    if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
