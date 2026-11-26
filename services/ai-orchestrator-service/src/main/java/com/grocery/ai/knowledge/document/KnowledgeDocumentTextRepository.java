package com.grocery.ai.knowledge.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KnowledgeDocumentTextRepository extends JpaRepository<KnowledgeDocumentText, UUID> {
}
