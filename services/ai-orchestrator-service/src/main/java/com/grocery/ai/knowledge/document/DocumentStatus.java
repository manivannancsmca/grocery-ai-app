package com.grocery.ai.knowledge.document;

/**
 * Where a document is in its life:
 * <pre>
 *   UPLOADED → PROCESSING → COMPLETED
 *                  ↓   ↑ (interrupted: tried again)
 *                FAILED
 *   any → DELETED (at once unsearchable)
 * </pre>
 * A new file or a reindex makes a COMPLETED or FAILED document UPLOADED again; the previous generation stays
 * searchable until the new one is COMPLETED.
 */
public enum DocumentStatus {
    UPLOADED, PROCESSING, COMPLETED, FAILED, DELETED
}
