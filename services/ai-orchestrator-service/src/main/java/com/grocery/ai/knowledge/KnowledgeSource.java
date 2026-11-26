package com.grocery.ai.knowledge;

import java.util.UUID;

/**
 * A help-center document an answer used, as the chat shows it under the answer ("From the help center: Refund
 * policy"). Only the title and category: never the file, the chunks or anything about the index.
 */
public record KnowledgeSource(UUID documentId, String title, KnowledgeCategory category) {
}
