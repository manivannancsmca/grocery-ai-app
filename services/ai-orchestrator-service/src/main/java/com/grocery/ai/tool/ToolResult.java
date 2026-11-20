package com.grocery.ai.tool;

import com.grocery.ai.link.PageLink;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** What a READ / LOW_RISK_WRITE tool produced. */
public sealed interface ToolResult {

    /**
     * The API answered: {@code result} is what the model gets (trimmed to what it needs; never a top-level
     * {@code status} field, which is reserved for APPROVAL_REQUIRED / BLOCKED / ERROR), {@code summary} is the
     * human-readable line streamed to the UI and audited, {@code links} are the app pages that show the same thing
     * (buttons under the answer).
     */
    record Ok(ObjectNode result, String summary, int httpStatus, List<PageLink> links) implements ToolResult {

        public Ok {
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    /**
     * The API refused (its {@code errorCode}, e.g. OUT_OF_STOCK) or could not be reached (SERVICE_UNAVAILABLE,
     * {@code httpStatus} null).
     */
    record Failed(String code, String message, Integer httpStatus) implements ToolResult {
    }
}
