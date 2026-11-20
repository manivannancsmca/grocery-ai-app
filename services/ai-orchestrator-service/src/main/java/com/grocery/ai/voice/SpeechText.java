package com.grocery.ai.voice;

/**
 * An assistant answer as text to read aloud: the answers are simple markdown, and a voice should say "View my cart",
 * not "open bracket View my cart close bracket slash cart". Lines become sentences.
 */
final class SpeechText {

    private SpeechText() {
    }

    static String plain(String markdown) {
        if (markdown == null) {
            return "";
        }
        String text = markdown
                .replaceAll("\\[([^\\]\\n]+)]\\([^)\\s]*\\)", "$1")     // [label](/path) -> label
                .replaceAll("`{1,3}", "")                                 // code marks
                .replaceAll("(\\*\\*|\\*)(\\S[^*\\n]*?)\\1", "$2")         // bold / italic (asterisks only: codes
                                                                          // such as LOW_STOCK keep their "_")
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
        StringBuilder spoken = new StringBuilder();
        for (String raw : text.split("\\n")) {
            String line = raw.replaceFirst("^\\s{0,3}#{1,6}\\s+", "")   // headings
                    .replaceFirst("^\\s*[-*+]\\s+", "")                  // list bullets
                    .replaceAll(" {2,}", " ")
                    .strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!spoken.isEmpty()) {
                char last = spoken.charAt(spoken.length() - 1);
                spoken.append(".!?:;,".indexOf(last) >= 0 ? " " : ". ");   // a pause between lines
            }
            spoken.append(line);
        }
        return spoken.toString();
    }
}
