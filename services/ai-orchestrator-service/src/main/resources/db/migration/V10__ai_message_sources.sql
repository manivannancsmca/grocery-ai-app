-- The help-center documents an ASSISTANT answer was given (knowledge base, docs/rag/RAG-MCP-INTEGRATION.md), as JSON:
-- [{"documentId": "…", "title": "Refund policy", "category": "POLICIES"}]. The chat shows them under the answer, also
-- when the conversation is opened again. NULL for every earlier message, for USER messages and for answers that used
-- no help-center article. Expand-only: code that does not know the column ignores it.
ALTER TABLE ai_message
    ADD COLUMN sources TEXT NULL AFTER unverified_figures;
