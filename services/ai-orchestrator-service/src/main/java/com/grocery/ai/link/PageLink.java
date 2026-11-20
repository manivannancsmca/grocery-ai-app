package com.grocery.ai.link;

import java.util.Objects;

/**
 * A page of the web app that an answer points to, shown as a clickable link under the answer: "View my cart" →
 * {@code /cart}. Build them with {@link Pages}, the one place that knows the app's URLs.
 *
 * @param label what the user reads, e.g. "Track order GA-7K3F9Q2M"
 * @param path  the app path with its query string, always starting with a single "/"
 */
public record PageLink(String label, String path) {

    public PageLink {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("A page link is an app path starting with one '/': " + path);
        }
    }
}
