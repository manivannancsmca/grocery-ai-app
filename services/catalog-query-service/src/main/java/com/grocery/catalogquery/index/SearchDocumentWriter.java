package com.grocery.catalogquery.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.json.JsonData;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.common.observability.ConsumedEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Writes search documents so that <b>an older event can never win</b>. Two ways, depending on who owns the document:
 *
 * <ul>
 *   <li>{@link #index} / {@link #delete} — one event owns the whole document (categories, restaurants). The event's
 *       version is Elasticsearch's <em>external version</em>: a write is kept only when its version is newer than
 *       the stored one.</li>
 *   <li>{@link #merge} — several events each own a <em>part</em> of the document (products: catalog snapshot, price,
 *       stock). Each part carries its own version field inside the document, and a script replaces the part only
 *       when the event's version is newer than that field. Parts never overwrite each other.</li>
 * </ul>
 *
 * Common rules:
 * <ul>
 *   <li>Writes target the ALIAS with {@code require_alias=true}: a deleted index is never auto-created with
 *       dynamic mappings. When the index is missing it is created (with alias) and the write is retried once.</li>
 *   <li>Stale or duplicate event: success (logged at debug), reported as {@code DUPLICATE} for
 *       {@code grocery_events_consumed_total} ({@link ConsumedEvents#markDuplicate()}; a write that changed the
 *       document reports the record as applied). Outside a Kafka record (admin re-index) the report is ignored.</li>
 *   <li>429 / 5xx / connection problems: {@link TransientEventProcessingException}, so the consumer keeps
 *       retrying the same record until the cluster is back (grocery-common error handler).</li>
 *   <li>Anything else (e.g. a mapping rejection) propagates as a processing error.</li>
 * </ul>
 */
@Component
public class SearchDocumentWriter {

    /**
     * Replaces one part of a document when {@code params.version} is newer than the version stored in
     * {@code params.versionField}; creates the document when it does not exist yet. The part's fields are removed
     * first, so a value that became null disappears instead of surviving from the previous version. A document
     * without a {@link #VISIBLE} flag — a placeholder created by a part that does not decide visibility (price,
     * stock) — gets {@code visible=false}, so it stays hidden until the part that owns the flag arrives.
     */
    static final String MERGE_SCRIPT = """
            if (ctx.op != 'create') {
              def stored = ctx._source[params.versionField];
              if (stored != null && stored >= params.version) { ctx.op = 'none'; return; }
            }
            for (def field : params.fields) { ctx._source.remove(field); }
            ctx._source.putAll(params.doc);
            if (!ctx._source.containsKey(params.visibleField)) {
              ctx._source[params.visibleField] = false;
            }
            """;

    /**
     * The three product listeners may update the same document at the same moment; Elasticsearch then re-runs the
     * script on the fresh document this many times before reporting a conflict.
     */
    static final int MERGE_RETRIES = 8;

    /** The visibility flag every searchable document carries; readers filter on {@code visible=true}. */
    public static final String VISIBLE = "visible";

    private static final Logger log = LoggerFactory.getLogger(SearchDocumentWriter.class);

    private final ElasticsearchClient client;
    private final IndexManager indexManager;

    public SearchDocumentWriter(ElasticsearchClient client, IndexManager indexManager) {
        this.client = client;
        this.indexManager = indexManager;
    }

    /** Indexes {@code document} as version {@code version} of {@code id}. */
    public void index(IndexDefinition definition, String id, long version, Object document) {
        String operation = "index " + definition.alias() + "/" + id + " v" + version;
        execute(definition, operation, true, () -> {
            indexManager.ensure(definition);
            client.index(i -> i
                    .index(definition.alias())
                    .id(id)
                    .version(version)
                    .versionType(VersionType.External)
                    .requireAlias(true)
                    .document(document));
            return true;
        });
    }

    /**
     * Writes one independently versioned part of document {@code id}: the fields in {@code ownedFields} are
     * replaced by {@code part} when {@code version} is newer than the document's {@code versionField}. A missing
     * document is created holding just this part (hidden, unless the part sets {@code visible} itself). Uses a
     * scripted upsert on the alias with {@code require_alias=true} and {@code retry_on_conflict}.
     *
     * @param part serialised like any document; must contain {@code versionField}
     */
    public void merge(IndexDefinition definition, String id, String versionField, long version,
                      Collection<String> ownedFields, Object part) {
        String operation = "merge " + definition.alias() + "/" + id + " " + versionField + "=" + version;
        List<String> fields = List.copyOf(ownedFields);
        // A conflict here is not a stale event (the script decides that): it means concurrent writers exhausted
        // the retries, so the record must be retried, never dropped.
        execute(definition, operation, false, () -> {
            indexManager.ensure(definition);
            // the script sets ctx.op = 'none' for a stale or duplicate part: Elasticsearch answers "noop"
            return client.update(u -> u
                            .index(definition.alias())
                            .id(id)
                            .requireAlias(true)
                            .retryOnConflict(MERGE_RETRIES)
                            .scriptedUpsert(true)
                            .upsert(Map.of())
                            .script(s -> s
                                    .lang("painless")
                                    .source(source -> source.scriptString(MERGE_SCRIPT))
                                    .params("versionField", JsonData.of(versionField))
                                    .params("version", JsonData.of(version))
                                    .params("fields", JsonData.of(fields))
                                    .params("visibleField", JsonData.of(VISIBLE))
                                    .params("doc", JsonData.of(part))),
                    Object.class).result() != Result.NoOp;
        });
    }

    /** Deletes {@code id} unless a newer version is stored (the delete itself is remembered as that version). */
    public void delete(IndexDefinition definition, String id, long version) {
        String operation = "delete " + definition.alias() + "/" + id + " v" + version;
        execute(definition, operation, true, () -> {
            // A versioned delete would auto-create a missing index under the alias name, so always verify the
            // alias first (deletes are rare).
            indexManager.recheck(definition);
            client.delete(d -> d
                    .index(definition.alias())
                    .id(id)
                    .version(version)
                    .versionType(VersionType.External));
            return true;
        });
    }

    /** Whether a document exists for {@code id} (false when the index is missing too). */
    public boolean exists(IndexDefinition definition, String id) {
        try {
            return client.exists(e -> e.index(definition.alias()).id(id)).value();
        } catch (ElasticsearchException e) {
            if (ElasticsearchErrors.isTransient(e)) {
                throw new TransientEventProcessingException("exists " + definition.alias() + "/" + id
                        + " failed: " + e.getMessage(), e);
            }
            throw e;
        } catch (IOException e) {
            throw new TransientEventProcessingException("Elasticsearch is unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * @param conflictMeansStale true for externally versioned writes (a version conflict = stale or duplicate
     *                           event = success); false for merges (a conflict = lost race, retry the record)
     */
    private void execute(IndexDefinition definition, String operation, boolean conflictMeansStale, Write write) {
        try {
            boolean changed;
            try {
                changed = write.run();
            } catch (ElasticsearchException e) {
                if (!ElasticsearchErrors.isIndexNotFound(e)) {
                    throw e;
                }
                log.warn("{}: no index behind alias '{}', creating it and retrying", operation, definition.alias());
                indexManager.recheck(definition);
                changed = write.run();
            }
            if (changed) {
                ConsumedEvents.report(true);
                log.debug("{}: done", operation);
            } else {
                ConsumedEvents.markDuplicate();
                log.debug("{}: skipped, the stored version is the same or newer", operation);
            }
        } catch (ElasticsearchException e) {
            if (ElasticsearchErrors.isVersionConflict(e)) {
                if (conflictMeansStale) {
                    ConsumedEvents.markDuplicate();
                    log.debug("{}: skipped, the stored version is the same or newer", operation);
                    return;
                }
                throw new TransientEventProcessingException(operation + " lost a race with concurrent writers: "
                        + e.getMessage(), e);
            }
            if (ElasticsearchErrors.isTransient(e)) {
                throw new TransientEventProcessingException(operation + " failed: " + e.getMessage(), e);
            }
            throw e;
        } catch (IOException e) {
            throw new TransientEventProcessingException(operation + " failed, Elasticsearch is unreachable: "
                    + e.getMessage(), e);
        }
    }

    /** One write; true when it changed the document, false when Elasticsearch skipped it (stale / duplicate). */
    @FunctionalInterface
    private interface Write {
        boolean run() throws IOException;
    }
}
