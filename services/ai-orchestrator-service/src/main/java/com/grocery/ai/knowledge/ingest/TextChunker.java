package com.grocery.ai.knowledge.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cuts cleaned text ({@link TextCleaner}) into chunks of about {@code chunkTokens} tokens, for embedding and search.
 * <ol>
 *   <li>The text is split into paragraphs (blank lines). A paragraph longer than a chunk is split into sentences,
 *       and a sentence longer than a chunk at word boundaries.</li>
 *   <li>These pieces are packed in order into chunks without passing the size.</li>
 *   <li>Every chunk after the first starts with the last sentences of the previous one (about
 *       {@code overlapTokens}), so a statement cut at a border is still whole in one chunk.</li>
 * </ol>
 * Tokens are estimated as {@value #CHARS_PER_TOKEN} characters (English prose); exact counts depend on the model.
 */
public final class TextChunker {

    public static final int CHARS_PER_TOKEN = 4;

    private static final Pattern PARAGRAPHS = Pattern.compile("\\n{2,}");
    /** After . ! ? or the Devanagari danda, before whitespace. */
    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?\\u0964])\\s+");
    private static final String PARAGRAPH_BREAK = "\n\n";

    private final int maxChars;
    private final int overlapChars;

    public TextChunker(int chunkTokens, int overlapTokens) {
        this.maxChars = chunkTokens * CHARS_PER_TOKEN;
        this.overlapChars = overlapTokens * CHARS_PER_TOKEN;
    }

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean currentHasNewText = false;
        for (String piece : pieces(text)) {
            int needed = current.isEmpty() ? piece.length() : current.length() + PARAGRAPH_BREAK.length()
                    + piece.length();
            if (needed > maxChars && currentHasNewText) {
                String finished = current.toString();
                chunks.add(finished);
                current.setLength(0);
                String overlap = tail(finished);
                if (!overlap.isEmpty() && overlap.length() + PARAGRAPH_BREAK.length() + piece.length() <= maxChars) {
                    current.append(overlap);
                }
                currentHasNewText = false;
            }
            if (!current.isEmpty()) {
                current.append(PARAGRAPH_BREAK);
            }
            current.append(piece);
            currentHasNewText = true;
        }
        if (currentHasNewText) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** Paragraphs, with the long ones split into sentences and the long sentences into words, none over a chunk. */
    private List<String> pieces(String text) {
        List<String> pieces = new ArrayList<>();
        for (String paragraph : PARAGRAPHS.split(text.strip())) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= maxChars) {
                pieces.add(trimmed);
                continue;
            }
            for (String sentence : SENTENCES.split(trimmed)) {
                if (sentence.length() <= maxChars) {
                    pieces.add(sentence);
                } else {
                    pieces.addAll(byWords(sentence, maxChars));
                }
            }
        }
        return pieces;
    }

    /** The last sentences of {@code chunk} that fit in the overlap (or its last words when one sentence is longer). */
    private String tail(String chunk) {
        if (overlapChars <= 0) {
            return "";
        }
        String[] sentences = SENTENCES.split(chunk.replace(PARAGRAPH_BREAK, " "));
        StringBuilder tail = new StringBuilder();
        for (int i = sentences.length - 1; i >= 0; i--) {
            String sentence = sentences[i].strip();
            int length = tail.isEmpty() ? sentence.length() : tail.length() + 1 + sentence.length();
            if (length > overlapChars) {
                break;
            }
            tail.insert(0, tail.isEmpty() ? sentence : sentence + " ");
        }
        if (tail.isEmpty()) {
            List<String> words = byWords(sentences[sentences.length - 1].strip(), overlapChars);
            return words.isEmpty() ? "" : words.getLast();
        }
        return tail.toString();
    }

    /** {@code text} in pieces of at most {@code limit} characters, cut between words (a longer word is cut). */
    private static List<String> byWords(String text, int limit) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (String word : text.split("\\s+")) {
            while (word.length() > limit) {
                if (!part.isEmpty()) {
                    parts.add(part.toString());
                    part.setLength(0);
                }
                parts.add(word.substring(0, limit));
                word = word.substring(limit);
            }
            int length = part.isEmpty() ? word.length() : part.length() + 1 + word.length();
            if (length > limit) {
                parts.add(part.toString());
                part.setLength(0);
            }
            if (!part.isEmpty()) {
                part.append(' ');
            }
            part.append(word);
        }
        if (!part.isEmpty()) {
            parts.add(part.toString());
        }
        return parts;
    }
}
