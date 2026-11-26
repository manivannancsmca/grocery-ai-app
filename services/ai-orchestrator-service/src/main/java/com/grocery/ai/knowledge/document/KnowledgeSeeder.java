package com.grocery.ai.knowledge.document;

import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeCategory;
import com.grocery.ai.knowledge.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads the starter help-center articles ({@code src/main/resources/knowledge/seed/*.md}) into an <em>empty</em>
 * knowledge base when {@code grocery.ai.knowledge.seed.enabled} is on (local development, Docker). Once anything was
 * ever uploaded (even if deleted since), it does nothing: what admins delete or replace stays so.
 *
 * <p>Each article starts with a small header:
 * <pre>
 * ---
 * title: Refunds and cancellations
 * category: POLICIES
 * audiences: CUSTOMER, STAFF
 * ---
 * </pre>
 */
@Component
public class KnowledgeSeeder {

    static final String LOCATION = "classpath:knowledge/seed/*.md";
    /** Who the starter articles are by (no admin uploaded them). */
    static final KnowledgeDocument.Editor SYSTEM = new KnowledgeDocument.Editor(new UUID(0, 0), "System");

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeeder.class);

    private final KnowledgeProperties properties;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentService service;

    public KnowledgeSeeder(KnowledgeProperties properties, KnowledgeDocumentRepository documents,
                           KnowledgeDocumentService service) {
        this.properties = properties;
        this.documents = documents;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (properties.enabled() && properties.seed().enabled()) {
            seed();
        }
    }

    /** Uploads every starter article into an empty knowledge base; the number uploaded. */
    public int seed() {
        if (documents.count() > 0) {
            return 0;
        }
        Resource[] articles;
        try {
            articles = new PathMatchingResourcePatternResolver().getResources(LOCATION);
        } catch (IOException e) {
            log.warn("Cannot list the starter help-center articles: {}", e.toString());
            return 0;
        }
        Arrays.sort(articles, Comparator.comparing(Resource::getFilename));
        int uploaded = 0;
        for (Resource article : articles) {
            try {
                Article parsed = parse(article.getContentAsString(StandardCharsets.UTF_8));
                service.upload(SYSTEM, article.getFilename(), parsed.body().getBytes(StandardCharsets.UTF_8),
                        new KnowledgeDocumentService.Details(parsed.title(), parsed.category(), parsed.audiences()));
                uploaded++;
            } catch (IOException | RuntimeException e) {
                log.warn("Starter help-center article {} not loaded: {}", article.getFilename(), e.toString());
            }
        }
        log.info("Loaded {} starter help-center articles into the empty knowledge base", uploaded);
        return uploaded;
    }

    record Article(String title, KnowledgeCategory category, Set<KnowledgeAudience> audiences, String body) {
    }

    /** The header (title, category, audiences) and the body of an article. */
    static Article parse(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("missing the --- header");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated --- header");
        }
        Map<String, String> header = new HashMap<>();
        for (String line : normalized.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                header.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        Set<KnowledgeAudience> audiences = EnumSet.noneOf(KnowledgeAudience.class);
        for (String audience : header.getOrDefault("audiences", "").split(",")) {
            if (!audience.isBlank()) {
                audiences.add(KnowledgeAudience.valueOf(audience.strip()));
            }
        }
        return new Article(header.get("title"), KnowledgeCategory.valueOf(header.get("category")), audiences,
                normalized.substring(end + 5).strip() + "\n");
    }
}
