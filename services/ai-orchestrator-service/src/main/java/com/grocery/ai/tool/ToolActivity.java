package com.grocery.ai.tool;

import com.grocery.ai.link.PageLink;

import java.util.List;
import java.util.UUID;

/**
 * The {@code tool} SSE event and the {@code tools} of a message in the conversation view (contract §2).
 *
 * @param label   human-readable, e.g. "Searched products"
 * @param summary one line about the outcome
 * @param links   app pages that show what the tool found (only when it succeeded), e.g. "View my cart" → /cart
 */
public record ToolActivity(UUID invocationId, String tool, String label, InvocationStatus status, String summary,
                           List<PageLink> links) {

    public ToolActivity {
        links = links == null ? List.of() : List.copyOf(links);
    }
}
