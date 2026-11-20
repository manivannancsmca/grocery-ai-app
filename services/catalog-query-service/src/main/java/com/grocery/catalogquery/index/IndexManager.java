package com.grocery.catalogquery.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates the versioned indexes and their aliases ({@code products_v1} behind {@code products}, …) from the JSON
 * definitions in {@code src/main/resources/elasticsearch/}, inlining {@code synonyms.txt} into the
 * {@code grocery_synonyms} filter.
 *
 * <p>Runs at startup without ever failing it (Elasticsearch may still be starting), and again on demand: before a
 * write, after a write found no index behind the alias, and when a search finds the alias missing.
 */
@Component
public class IndexManager {

    static final String SYNONYMS_RESOURCE = "elasticsearch/synonyms.txt";
    static final String SYNONYM_FILTER = "grocery_synonyms";

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private final ElasticsearchClient client;
    private final JsonMapper jsonMapper;
    /** Aliases known to exist; cleared for an alias when Elasticsearch reports its index missing. */
    private final Set<IndexDefinition> ready = ConcurrentHashMap.newKeySet();

    public IndexManager(ElasticsearchClient client, JsonMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createMissingIndexesAtStartup() {
        for (IndexDefinition definition : IndexDefinition.values()) {
            if (!ensureQuietly(definition)) {
                log.warn("Search index '{}' is not ready yet; it will be created on first use", definition.alias());
            }
        }
    }

    /** Makes sure the alias (and its index) exists. Free once it has been seen. */
    public void ensure(IndexDefinition definition) throws IOException {
        if (ready.contains(definition)) {
            return;
        }
        synchronized (this) {
            if (!ready.contains(definition)) {
                createIfMissing(definition);
                ready.add(definition);
            }
        }
    }

    /** Forgets what we knew (Elasticsearch said the index is gone) and checks / creates again. */
    public void recheck(IndexDefinition definition) throws IOException {
        ready.remove(definition);
        ensure(definition);
    }

    /** {@link #recheck} for read paths: logs instead of throwing. Returns whether the alias is ready. */
    public boolean ensureQuietly(IndexDefinition definition) {
        try {
            recheck(definition);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot prepare search index '{}': {}", definition.alias(), e.getMessage());
            return false;
        }
    }

    private void createIfMissing(IndexDefinition definition) throws IOException {
        String alias = definition.alias();
        String index = definition.index();
        if (client.indices().existsAlias(a -> a.name(alias)).value()) {
            addNewFields(definition);
            return;
        }
        if (client.indices().exists(e -> e.index(index)).value()) {
            // The concrete index survived but lost its alias: attach it again.
            client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add
                    .index(index).alias(alias).isWriteIndex(true))));
            log.info("Re-attached alias '{}' to existing index '{}'", alias, index);
            addNewFields(definition);
            return;
        }
        String body = definitionJson(definition);
        try {
            client.indices().create(c -> c
                    .index(index)
                    .withJson(new StringReader(body))
                    .aliases(alias, a -> a.isWriteIndex(true)));
            log.info("Created search index '{}' behind alias '{}'", index, alias);
        } catch (ElasticsearchException e) {
            if (!ElasticsearchErrors.isAlreadyExists(e)) {
                throw e;
            }
            log.debug("Index '{}' was created concurrently", index);
        }
    }

    /**
     * An index created by an older release misses the fields added since (e.g. price and stock in Phase 4), and
     * the strict mapping would reject documents that carry them. Sending the definition's field mappings again
     * (put-mapping, idempotent) adds whatever is new and leaves existing fields as they are. A change
     * Elasticsearch cannot apply in place (a different type or analyzer for an existing field) is only logged:
     * that needs a new index version ({@code _v2}) and a republish. An outage (429 / 5xx) propagates, so the
     * alias is not marked ready and the next write tries again.
     */
    private void addNewFields(IndexDefinition definition) throws IOException {
        String body = mappingPropertiesJson(definition);
        if (body == null) {
            return;
        }
        try {
            client.indices().putMapping(p -> p.index(definition.alias()).withJson(new StringReader(body)));
            log.debug("Field mappings of '{}' are up to date", definition.alias());
        } catch (ElasticsearchException e) {
            if (ElasticsearchErrors.isTransient(e) || ElasticsearchErrors.isIndexNotFound(e)) {
                throw e;
            }
            log.warn("Cannot bring the field mappings of '{}' up to date: {}", definition.alias(), e.getMessage());
        }
    }

    /** {@code {"properties": {…}}} of the definition, or null when it declares no fields. */
    String mappingPropertiesJson(IndexDefinition definition) {
        JsonNode properties = jsonMapper.readTree(read(definition.resource())).at("/mappings/properties");
        if (!(properties instanceof ObjectNode)) {
            return null;
        }
        ObjectNode body = jsonMapper.createObjectNode();
        body.set("properties", properties);
        return jsonMapper.writeValueAsString(body);
    }

    /** The index definition (settings + mappings) with the synonym rules inlined, as sent on index creation. */
    public String definitionJson(IndexDefinition definition) {
        JsonNode root = jsonMapper.readTree(read(definition.resource()));
        JsonNode filter = root.at("/settings/analysis/filter/" + SYNONYM_FILTER);
        if (filter instanceof ObjectNode synonymFilter) {
            ArrayNode rules = synonymFilter.putArray("synonyms");
            synonyms().forEach(rules::add);
        }
        return jsonMapper.writeValueAsString(root);
    }

    /** Solr-format rules from synonyms.txt, without comments and blank lines. */
    List<String> synonyms() {
        return read(SYNONYMS_RESOURCE).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private static String read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            reader.lines().forEach(line -> text.append(line).append('\n'));
            return text.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
