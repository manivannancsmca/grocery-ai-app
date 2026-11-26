-- The knowledge base (RAG, docs/rag/RAG-DOCUMENT-INGESTION.md): help-center documents admins upload. MySQL keeps the
-- documents, their text and their chunks' bookkeeping; the chunks' text and vectors live in this service's own
-- Elasticsearch index (ADR-0015), never here. Nothing in these tables is business data of another service.
-- IDs: UUIDv7 as BINARY(16); timestamps DATETIME(6) in UTC.

-- One uploaded document. content_version counts the files uploaded for it (1, 2, …); generation counts the times it
-- was (re)indexed. indexed_generation is the generation whose chunks searches may return: an update keeps serving
-- the previous generation until the new one is complete, and a deleted document has none.
CREATE TABLE knowledge_document (
    id                  BINARY(16)   NOT NULL,
    title               VARCHAR(200) NOT NULL,
    category            VARCHAR(20)  NOT NULL,            -- APP_GUIDE | PRODUCT_GUIDE | POLICIES | VENDOR | DELIVERY | SUPPORT
    audiences           VARCHAR(100) NOT NULL,            -- comma-separated: CUSTOMER,VENDOR,RESTAURANT_OWNER,DELIVERY_PARTNER,STAFF
    file_name           VARCHAR(255) NOT NULL,            -- as uploaded (shown to admins only; files are not kept)
    format              VARCHAR(10)  NOT NULL,            -- PDF | DOCX | TXT | MARKDOWN | HTML
    size_bytes          BIGINT       NOT NULL,
    content_sha256      CHAR(64)     NOT NULL,            -- of the file: the same file again is not embedded twice
    content_version     INT          NOT NULL,
    generation          INT          NOT NULL,
    indexed_generation  INT          NULL,
    status              VARCHAR(12)  NOT NULL,            -- UPLOADED | PROCESSING | COMPLETED | FAILED | DELETED
    chunk_count         INT          NOT NULL,            -- of indexed_generation
    embedding_model     VARCHAR(100) NULL,                -- of indexed_generation
    failure_reason      VARCHAR(500) NULL,
    attempts            INT          NOT NULL,            -- ingestion attempts of the current generation
    processing_since    DATETIME(6)  NULL,
    created_by          BINARY(16)   NOT NULL,
    created_by_name     VARCHAR(200) NOT NULL,
    updated_by          BINARY(16)   NOT NULL,
    updated_by_name     VARCHAR(200) NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    processed_at        DATETIME(6)  NULL,
    deleted_at          DATETIME(6)  NULL,
    lock_version        BIGINT       NOT NULL,            -- optimistic lock
    CONSTRAINT pk_knowledge_document PRIMARY KEY (id),
    INDEX idx_knowledge_document_status_updated (status, updated_at),   -- the ingestion worker's claim
    INDEX idx_knowledge_document_sha (content_sha256),
    INDEX idx_knowledge_document_updated (updated_at),
    INDEX idx_knowledge_document_category (category, updated_at)
) ENGINE = InnoDB;

-- The text extracted from a document's latest file (what is chunked; enough to reindex without the file).
CREATE TABLE knowledge_document_text (
    document_id      BINARY(16)  NOT NULL,
    content_version  INT         NOT NULL,
    content          MEDIUMTEXT  NOT NULL,
    CONSTRAINT pk_knowledge_document_text PRIMARY KEY (document_id)
) ENGINE = InnoDB;

-- One chunk in the knowledge index (id = the Elasticsearch document id). Rows of a generation are written when it
-- completes and removed with its chunks in the index.
CREATE TABLE knowledge_chunk (
    id           BINARY(16)  NOT NULL,
    document_id  BINARY(16)  NOT NULL,
    generation   INT         NOT NULL,
    chunk_index  INT         NOT NULL,
    characters   INT         NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_knowledge_chunk PRIMARY KEY (id),
    INDEX idx_knowledge_chunk_document (document_id, generation, chunk_index)
) ENGINE = InnoDB;
