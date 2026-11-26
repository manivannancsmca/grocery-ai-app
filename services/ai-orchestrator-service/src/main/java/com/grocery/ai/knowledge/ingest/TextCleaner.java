package com.grocery.ai.knowledge.ingest;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Tidies extracted text before it is chunked: one Unicode form, {@code \n} line ends, no control or zero-width
 * characters, no word split over two lines by a hyphen (PDFs), single spaces, trimmed lines, and at most one blank
 * line between paragraphs. Paragraphs (blank lines) are kept: the chunker cuts there first.
 */
public final class TextCleaner {

    /** Control characters except tab and newline, and the zero-width ones. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|[\\u200B-\\u200D\\u2060\\uFEFF]");
    /** "deli-\nvery" → "delivery" (a lower-case letter continues the word). */
    private static final Pattern HYPHENATED_LINE_BREAK = Pattern.compile("(\\p{L})-\\n(\\p{Ll})");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0\\u2007\\u202F]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private TextCleaner() {
    }

    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        cleaned = INVISIBLE.matcher(cleaned).replaceAll("");
        cleaned = HYPHENATED_LINE_BREAK.matcher(cleaned).replaceAll("$1$2");
        StringBuilder lines = new StringBuilder(cleaned.length());
        for (String line : cleaned.split("\n", -1)) {
            lines.append(SPACES.matcher(line).replaceAll(" ").strip()).append('\n');
        }
        return BLANK_LINES.matcher(lines).replaceAll("\n\n").strip();
    }
}
