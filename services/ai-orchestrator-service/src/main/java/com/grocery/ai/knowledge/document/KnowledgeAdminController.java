package com.grocery.ai.knowledge.document;

import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeCategory;
import com.grocery.ai.knowledge.KnowledgeException;
import com.grocery.ai.knowledge.KnowledgeProperties;
import com.grocery.ai.knowledge.search.KnowledgeSearch;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The knowledge base's documents (docs/rag/RAG-API-REFERENCE.md). Any admin reads; SUPER_ADMIN and OPS_ADMIN change
 * (the gateway already keeps non-admins out of {@code /api/admin/**}).
 */
@RestController
@RequestMapping("/api/admin/ai/knowledge")
@PreAuthorize(Roles.ANY_ADMIN)
public class KnowledgeAdminController {

    private final KnowledgeDocumentService documents;
    private final KnowledgeSearch search;
    private final KnowledgeProperties properties;

    public KnowledgeAdminController(KnowledgeDocumentService documents, KnowledgeSearch search,
                                    KnowledgeProperties properties) {
        this.documents = documents;
        this.search = search;
        this.properties = properties;
    }

    /** Whether the knowledge base is on and reachable, and its documents per status (also when it is off). */
    @GetMapping("/status")
    public KnowledgeDocumentService.Status status() {
        return documents.status();
    }

    /** Newest change first; {@code status} = DELETED lists the deleted ones (otherwise they are left out). */
    @GetMapping("/documents")
    public PageResponse<DocumentView> list(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String category,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return documents.list(BadRequestException.parseEnum(DocumentStatus.class, "status", status),
                BadRequestException.parseEnum(KnowledgeCategory.class, "category", category), page, size);
    }

    @GetMapping("/documents/{id}")
    public DocumentView get(@PathVariable UUID id) {
        return documents.get(id);
    }

    /** 202: stored, indexing in the background. 200: the same file is already in the knowledge base. */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public ResponseEntity<DocumentView> upload(@RequestPart("file") MultipartFile file,
                                               @RequestParam(required = false) String title,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) List<String> audiences,
                                               CurrentUser admin) {
        KnowledgeDocumentService.UploadResult result = documents.upload(editor(admin), file.getOriginalFilename(),
                bytes(file), new KnowledgeDocumentService.Details(title, category(category), audiences(audiences)));
        return ResponseEntity.status(result.changed() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.document());
    }

    /** A new version of a document; title, category and audiences are kept unless given. */
    @PutMapping(value = "/documents/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public ResponseEntity<DocumentView> replace(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                                @RequestParam(required = false) String title,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) List<String> audiences,
                                                CurrentUser admin) {
        KnowledgeDocumentService.UploadResult result = documents.replace(id, editor(admin),
                file.getOriginalFilename(), bytes(file),
                new KnowledgeDocumentService.Details(title, category(category), audiences(audiences)));
        return ResponseEntity.status(result.changed() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.document());
    }

    @PostMapping("/documents/{id}/reindex")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public ResponseEntity<DocumentView> reindex(@PathVariable UUID id, CurrentUser admin) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(documents.reindex(id, editor(admin)));
    }

    @DeleteMapping("/documents/{id}")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public ResponseEntity<Void> delete(@PathVariable UUID id, CurrentUser admin) {
        documents.delete(id, editor(admin));
        return ResponseEntity.noContent().build();
    }

    /**
     * Test the retrieval as a reader would get it: {@code audience} (default every audience), an optional
     * {@code category}, {@code topK} (1–20) and {@code minSimilarity} (0–1) default to the configured values. The
     * status is FOUND or NO_MATCH (what the assistant then says).
     */
    @PostMapping("/search")
    public SearchView search(@RequestBody SearchRequest request) {
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty() || question.length() > 1000) {
            throw KnowledgeException.validation("question", "size must be between 1 and 1000");
        }
        int topK = request.topK() == null ? properties.retrieval().topK() : request.topK();
        if (topK < 1 || topK > 20) {
            throw KnowledgeException.validation("topK", "must be between 1 and 20");
        }
        double minSimilarity = request.minSimilarity() == null ? properties.retrieval().similarityThreshold()
                : request.minSimilarity();
        if (minSimilarity < 0 || minSimilarity > 1) {
            throw KnowledgeException.validation("minSimilarity", "must be between 0 and 1");
        }
        KnowledgeAudience audience = BadRequestException.parseEnum(KnowledgeAudience.class, "audience",
                request.audience());
        Set<KnowledgeAudience> audiences = audience == null ? EnumSet.allOf(KnowledgeAudience.class)
                : EnumSet.of(audience);
        KnowledgeSearch.Result result = search.search(question, audiences, category(request.category()), topK,
                minSimilarity);
        return switch (result.status()) {
            case DISABLED -> throw KnowledgeException.disabled();
            case UNAVAILABLE -> throw KnowledgeException.unavailable();
            case FOUND, NO_MATCH -> new SearchView(result.status().name(), result.snippets());
        };
    }

    public record SearchRequest(String question, String audience, String category, Integer topK,
                                Double minSimilarity) {
    }

    /** {@code status}: FOUND or NO_MATCH; {@code results}: best first. */
    public record SearchView(String status, List<KnowledgeSearch.Snippet> results) {
    }

    private static KnowledgeDocument.Editor editor(CurrentUser admin) {
        String name = admin.name() == null || admin.name().isBlank() ? admin.email() : admin.name();
        return new KnowledgeDocument.Editor(admin.userId(), name == null ? "Admin" : name);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw KnowledgeException.invalid("The file could not be read.");
        }
    }

    private static KnowledgeCategory category(String value) {
        return BadRequestException.parseEnum(KnowledgeCategory.class, "category", value);
    }

    /** Repeated ({@code audiences=CUSTOMER&audiences=VENDOR}) or comma-separated; null when none given. */
    private static Set<KnowledgeAudience> audiences(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<KnowledgeAudience> audiences = EnumSet.noneOf(KnowledgeAudience.class);
        for (String value : values) {
            for (String part : value.split(",")) {
                KnowledgeAudience audience = BadRequestException.parseEnum(KnowledgeAudience.class, "audience", part);
                if (audience != null) {
                    audiences.add(audience);
                }
            }
        }
        return audiences;
    }
}
