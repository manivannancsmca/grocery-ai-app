package com.grocery.review.service;

import com.grocery.review.config.ReviewProperties;
import com.grocery.review.entity.Review.Moderation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Automatic moderation (contract §1). A review whose title or comment contains an email address, a link, a phone
 * number, a word of the profanity list, or more than 70 % upper-case letters is {@code FLAGGED} (hidden until an admin
 * decides); anything else is {@code PUBLISHED} at once. The first matching rule, in that order, is the flag reason.
 */
@Component
public class AutoModerator {

    public static final String EMAIL = "EMAIL";
    public static final String LINK = "LINK";
    public static final String PHONE = "PHONE";
    public static final String PROFANITY = "PROFANITY";
    public static final String UPPER_CASE = "UPPER_CASE";

    static final Pattern EMAIL_PATTERN = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");
    /** A scheme or "www.", or a bare domain with a common top-level domain ("shop.example.in", "bit.ly/x"). */
    static final Pattern LINK_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:https?|ftp)://|\\bwww\\.|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|in|co|io|info|biz"
                    + "|app|xyz|shop|store|online|site|link|ly|gl|uk|ai)\\b)");
    /**
     * 10 or more digits, optionally with a leading + and up to two spaces, dots, dashes or brackets between them
     * ("98765 43210", "+91-98765-43210", "(080) 2345-6789"); not followed by ':' (a date and time such as
     * "2026-11-13 10:30" is not a phone number).
     */
    static final Pattern PHONE_PATTERN = Pattern.compile("(?<![\\p{N}])\\+?\\d(?:[\\s().-]{0,2}\\d){9,}(?![\\p{N}:])");
    private static final Pattern WORD_SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final Set<String> words;
    private final double upperCaseRatio;
    private final int upperCaseMinLetters;

    @Autowired
    public AutoModerator(ReviewProperties properties, ResourceLoader resources) {
        this(loadWords(resources.getResource(properties.moderation().words())),
                properties.moderation().upperCaseRatio(), properties.moderation().upperCaseMinLetters());
    }

    AutoModerator(Set<String> words, double upperCaseRatio, int upperCaseMinLetters) {
        this.words = Set.copyOf(words);
        this.upperCaseRatio = upperCaseRatio;
        this.upperCaseMinLetters = upperCaseMinLetters;
    }

    /** PUBLISHED, or FLAGGED with the reason; title and comment may be null. */
    public Moderation moderate(String title, String comment) {
        String text = (title == null ? "" : title) + "\n" + (comment == null ? "" : comment);
        String reason = flagReason(text);
        return reason == null ? Moderation.PUBLISHED : Moderation.flagged(reason);
    }

    String flagReason(String text) {
        if (EMAIL_PATTERN.matcher(text).find()) {
            return EMAIL;
        }
        if (LINK_PATTERN.matcher(text).find()) {
            return LINK;
        }
        if (PHONE_PATTERN.matcher(text).find()) {
            return PHONE;
        }
        if (containsListedWord(text)) {
            return PROFANITY;
        }
        if (mostlyUpperCase(text)) {
            return UPPER_CASE;
        }
        return null;
    }

    private boolean containsListedWord(String text) {
        for (String word : WORD_SEPARATORS.split(text.toLowerCase(Locale.ROOT))) {
            if (!word.isEmpty() && words.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** More than {@code upperCaseRatio} of the letters are upper case, once there are enough letters to judge. */
    private boolean mostlyUpperCase(String text) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                letters++;
                if (Character.isUpperCase(cp)) {
                    upper++;
                }
            }
            i += Character.charCount(cp);
        }
        return letters >= upperCaseMinLetters && (double) upper / letters > upperCaseRatio;
    }

    /** One word per line, lower-cased; blank lines and {@code #} comments skipped. */
    static Set<String> loadWords(Resource resource) {
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            return content.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the moderation word list " + resource, e);
        }
    }
}
