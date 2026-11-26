package com.grocery.ai.knowledge.document;

import java.util.Locale;
import java.util.Optional;

/** The file types the knowledge base accepts, by file extension (the content is checked too: DocumentValidator). */
public enum DocumentFormat {
    PDF("pdf"),
    DOCX("docx"),
    TXT("txt"),
    MARKDOWN("md", "markdown"),
    HTML("html", "htm");

    private final String[] extensions;

    DocumentFormat(String... extensions) {
        this.extensions = extensions;
    }

    /** The format of {@code fileName}'s extension; empty for anything else (.doc, .exe, no extension…). */
    public static Optional<DocumentFormat> ofFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            for (String candidate : format.extensions) {
                if (candidate.equals(extension)) {
                    return Optional.of(format);
                }
            }
        }
        return Optional.empty();
    }
}
