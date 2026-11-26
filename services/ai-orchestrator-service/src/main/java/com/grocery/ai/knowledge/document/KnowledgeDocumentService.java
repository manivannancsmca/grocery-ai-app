package com.grocery.ai.knowledge.document;

import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeCategory;
import com.grocery.ai.knowledge.KnowledgeException;
import com.grocery.ai.knowledge.KnowledgeProperties;
import com.grocery.ai.knowledge.index.KnowledgeIndex;
import com.grocery.ai.knowledge.ingest.DocumentValidator;
import com.grocery.ai.knowledge.ingest.IngestionWorker;
import com.grocery.ai.knowledge.ingest.TextCleaner;
import com.grocery.ai.knowledge.ingest.TextExtractor;
import com.grocery.common.data.Pages;
import com.grocery.common.id.Ids;
import com.grocery.common.web.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The admin side of the knowledge base (docs/rag/RAG-API-REFERENCE.md): upload, replace, reindex, delete and list
 * documents. An upload is checked and its text extracted right away (a bad file is refused with a 4xx); embedding and
 * indexing happen in the background ({@link IngestionWorker}), so the request returns 202 at once.
 *
 * <p>The same file uploaded again (same SHA-256) creates nothing and embeds nothing: the existing document is returned
 * with 200.
 */
@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final int MAX_TITLE_LENGTH = 200;

    private final KnowledgeProperties properties;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentTextRepository texts;
    private final KnowledgeChunkRepository chunks;
    private final DocumentValidator validator;
    private final TextExtractor extractor;
    private final KnowledgeIndex index;
    private final IngestionWorker worker;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public KnowledgeDocumentService(KnowledgeProperties properties, KnowledgeDocumentRepository documents,
                                    KnowledgeDocumentTextRepository texts, KnowledgeChunkRepository chunks,
                                    DocumentValidator validator, TextExtractor extractor, KnowledgeIndex index,
                                    IngestionWorker worker, TransactionTemplate transaction, Clock clock) {
        this.properties = properties;
        this.documents = documents;
        this.texts = texts;
        this.chunks = chunks;
        this.validator = validator;
        this.extractor = extractor;
        this.index = index;
        this.worker = worker;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** What an upload or a replacement did: {@code changed} = false when it was the same file (nothing to index). */
    public record UploadResult(DocumentView document, boolean changed) {
    }

    /** The details an admin gives with a file; null = keep the document's own (replacement) or derive (title). */
    public record Details(String title, KnowledgeCategory category, Set<KnowledgeAudience> audiences) {
    }

    public UploadResult upload(KnowledgeDocument.Editor by, String fileName, byte[] content, Details details) {
        requireEnabled();
        String title = title(details.title(), fileName);
        KnowledgeCategory category = required(details.category(), "category");
        Set<KnowledgeAudience> audiences = audiences(details.audiences());
        DocumentFormat format = validator.validate(fileName, content);
        String sha256 = sha256(content);
        var duplicate = documents.findFirstByContentSha256AndDeletedAtIsNullOrderByCreatedAt(sha256);
        if (duplicate.isPresent()) {
            log.info("Knowledge upload of '{}' is the same file as document {}: nothing to index", fileName,
                    duplicate.get().getId());
            return new UploadResult(DocumentView.of(duplicate.get()), false);
        }
        String text = text(format, content);
        KnowledgeDocument.UploadedFile file = new KnowledgeDocument.UploadedFile(cleanFileName(fileName), format,
                content.length, sha256);
        KnowledgeDocument saved = transaction.execute(status -> {
            KnowledgeDocument document = KnowledgeDocument.upload(Ids.newId(), title, category, audiences, file, by,
                    clock.instant());
            documents.save(document);
            texts.save(new KnowledgeDocumentText(document.getId(), document.getContentVersion(), text));
            return document;
        });
        log.info("Knowledge document {} '{}' uploaded ({}, {} characters of text) by {}", saved.getId(), title,
                format, text.length(), by.id());
        worker.wake();
        return new UploadResult(DocumentView.of(saved), true);
    }

    /** A new file for a document (and optionally new details). The same file with the same details changes nothing. */
    public UploadResult replace(UUID id, KnowledgeDocument.Editor by, String fileName, byte[] content,
                                Details details) {
        requireEnabled();
        KnowledgeDocument current = existing(id);
        String title = details.title() == null || details.title().isBlank() ? current.getTitle()
                : title(details.title(), fileName);
        KnowledgeCategory category = details.category() == null ? current.getCategory() : details.category();
        Set<KnowledgeAudience> audiences = details.audiences() == null || details.audiences().isEmpty()
                ? current.getAudiences() : audiences(details.audiences());
        DocumentFormat format = validator.validate(fileName, content);
        String sha256 = sha256(content);
        boolean sameFile = sha256.equals(current.getContentSha256());
        if (sameFile && current.hasDetails(title, category, audiences)) {
            return new UploadResult(DocumentView.of(current), false);
        }
        String text = sameFile ? null : text(format, content);
        KnowledgeDocument.UploadedFile file = new KnowledgeDocument.UploadedFile(cleanFileName(fileName), format,
                content.length, sha256);
        KnowledgeDocument saved = write(() -> {
            KnowledgeDocument document = existing(id);
            if (sameFile) {
                document.changeDetails(title, category, audiences, by, clock.instant());
            } else {
                document.replaceFile(file, title, category, audiences, by, clock.instant());
                KnowledgeDocumentText stored = texts.findById(id).orElse(null);
                if (stored == null) {
                    texts.save(new KnowledgeDocumentText(id, document.getContentVersion(), text));
                } else {
                    stored.replace(document.getContentVersion(), text);
                }
            }
            return document;
        });
        log.info("Knowledge document {} {} by {}", id, sameFile ? "details changed" : "replaced (version "
                + saved.getContentVersion() + ")", by.id());
        worker.wake();
        return new UploadResult(DocumentView.of(saved), true);
    }

    /** Index again from the stored text (after changing chunk sizes or the embedding model). */
    public DocumentView reindex(UUID id, KnowledgeDocument.Editor by) {
        requireEnabled();
        KnowledgeDocument saved = write(() -> {
            KnowledgeDocument document = existing(id);
            document.requestIngestion(by, clock.instant());
            return document;
        });
        worker.wake();
        return DocumentView.of(saved);
    }

    /**
     * Deletes a document: unsearchable as soon as this commits (searches only accept indexed generations of documents
     * that are not deleted), then its chunks are removed from the index. If that fails, the ingestion worker's
     * clean-up tries again.
     */
    public void delete(UUID id, KnowledgeDocument.Editor by) {
        requireEnabled();
        write(() -> {
            KnowledgeDocument document = existing(id);
            document.delete(by, clock.instant());
            return document;
        });
        log.info("Knowledge document {} deleted by {}", id, by.id());
        try {
            index.deleteDocument(id);
            transaction.executeWithoutResult(status -> chunks.deleteByDocument(id));
        } catch (Exception e) {
            log.warn("Chunks of deleted knowledge document {} stay in the index until the clean-up removes them: {}",
                    id, e.toString());
        }
    }

    /**
     * The knowledge base's state for admins (docs/rag/RAG-API-REFERENCE.md, "Status"): whether it is on, whether
     * Elasticsearch answers, the embedding model and the documents per status. Never fails: an outage is a field.
     */
    public record Status(boolean enabled, boolean elasticsearchReachable, String embeddingModel, String index,
                         Map<String, Long> documents) {
    }

    public Status status() {
        if (!properties.enabled()) {
            return new Status(false, false, null, null, Map.of());
        }
        return new Status(true, index.reachable(), properties.embedding().model(), properties.indexAlias(),
                documents.countsByStatus());
    }

    public DocumentView get(UUID id) {
        requireEnabled();
        return DocumentView.of(documents.findById(id).orElseThrow(() -> KnowledgeException.notFound(id)));
    }

    /** Newest change first; deleted documents only when {@code status} = DELETED. */
    public PageResponse<DocumentView> list(DocumentStatus status, KnowledgeCategory category, Integer page,
                                           Integer size) {
        requireEnabled();
        Specification<KnowledgeDocument> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status == null) {
                predicates.add(cb.isNull(root.get("deletedAt")));
            } else {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return Pages.toResponse(documents.findAll(filter, Pages.request(page, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))), DocumentView::of);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw KnowledgeException.disabled();
        }
    }

    private KnowledgeDocument existing(UUID id) {
        KnowledgeDocument document = documents.findById(id).orElseThrow(() -> KnowledgeException.notFound(id));
        if (document.isDeleted()) {
            throw KnowledgeException.notFound(id);
        }
        return document;
    }

    /** One change in a transaction; a concurrent change (the worker, another admin) is a 409. */
    private KnowledgeDocument write(Supplier<KnowledgeDocument> change) {
        try {
            return transaction.execute(status -> change.get());
        } catch (OptimisticLockingFailureException e) {
            throw KnowledgeException.conflict("The document was changed at the same time. Reload it and try again.");
        }
    }

    /** The cleaned text of the file, or 400 when it has none or too much. */
    private String text(DocumentFormat format, byte[] content) {
        String text = TextCleaner.clean(extractor.extract(format, content));
        if (text.isBlank()) {
            throw KnowledgeException.empty();
        }
        int max = properties.upload().maxTextCharacters();
        if (text.length() > max) {
            throw KnowledgeException.tooLong(max);
        }
        return text;
    }

    private static String title(String given, String fileName) {
        String title = given == null || given.isBlank() ? titleFromFileName(fileName) : given.strip();
        if (title.isEmpty()) {
            throw KnowledgeException.validation("title", "must not be blank");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw KnowledgeException.validation("title", "size must be at most " + MAX_TITLE_LENGTH);
        }
        return title;
    }

    /** "refund-policy_v2.pdf" → "refund policy v2". */
    static String titleFromFileName(String fileName) {
        String name = cleanFileName(fileName);
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replaceAll("[-_]+", " ").strip();
    }

    /** The file name without any path a browser may send ("C:\fakepath\x.pdf"), at most 255 characters. */
    static String cleanFileName(String fileName) {
        String name = fileName == null ? "" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw KnowledgeException.validation(field, "is required");
        }
        return value;
    }

    private static Set<KnowledgeAudience> audiences(Set<KnowledgeAudience> audiences) {
        if (audiences == null || audiences.isEmpty()) {
            throw KnowledgeException.validation("audiences", "name at least one audience");
        }
        return audiences;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
