package com.grocery.ai.chat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A safety net against invented data: in a turn where no tool returned data, the answer's figures — numbers of two or
 * more digits, and business references such as order numbers (GA-7K3F9Q2M) or purchase orders (PO-…) — must
 * already appear in what the model was given about this user: their message, the conversation so far (which keeps
 * earlier tool results), the server's context line (with its live snapshot) or the tools' refusals. A figure from
 * nowhere ("112 vendors are approved", an order number copied from an example) is reported, and the answer is marked
 * as not checked against the app's data.
 *
 * <p>Deliberately narrow: turns that called a tool successfully are not checked (the model had real data and may
 * derive from it, e.g. a time in India from a UTC instant), and single digits are ignored ("step 1", "2 options").
 */
final class Grounding {

    /** 112, 1,890.50, 4.5, 2026 (a date's parts count separately). */
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    /** GA-7K3F9Q2M, PO-2026-0042: letters, a dash, then letters and digits with at least one digit. */
    private static final Pattern REFERENCE = Pattern.compile("\\b[A-Z]{2,4}-(?=[A-Z0-9-]*\\d)[A-Z0-9][A-Z0-9-]{4,30}\\b",
            Pattern.CASE_INSENSITIVE);

    private Grounding() {
    }

    /** The answer's figures (as written) that none of {@code sources} contains, in order, without repeats. */
    static List<String> ungroundedFigures(String answer, List<String> sources) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        Set<String> knownNumbers = new HashSet<>();
        StringBuilder knownText = new StringBuilder();
        for (String source : sources) {
            if (source != null) {
                knownNumbers.addAll(numbers(source));
                knownText.append(source.toUpperCase(Locale.ROOT)).append('\n');
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher references = REFERENCE.matcher(answer);
        while (references.find()) {
            if (knownText.indexOf(references.group().toUpperCase(Locale.ROOT)) < 0) {
                missing.add(references.group());
            }
        }
        // Numbers inside a reference were judged with it.
        Matcher matcher = NUMBER.matcher(REFERENCE.matcher(answer).replaceAll(" "));
        while (matcher.find()) {
            String written = trimSeparators(matcher.group());
            String value = normalize(written);
            if (value != null && value.replace(".", "").replace("-", "").length() >= 2
                    && !knownNumbers.contains(value)) {
                missing.add(written);
            }
        }
        return new ArrayList<>(missing);
    }

    private static Set<String> numbers(String text) {
        Set<String> values = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String value = normalize(trimSeparators(matcher.group()));
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /** "1,890.00" → "1890", "4.50" → "4.5": the same amount however it is written. */
    private static String normalize(String number) {
        try {
            return new BigDecimal(number.replace(",", "")).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A sentence's trailing comma or full stop is not part of the number ("112," → "112"). */
    private static String trimSeparators(String number) {
        String trimmed = number;
        while (!trimmed.isEmpty() && (trimmed.endsWith(",") || trimmed.endsWith("."))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
