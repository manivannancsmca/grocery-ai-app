package com.grocery.catalogquery.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.get.GetResult;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Read access for the public and internal endpoints. A missing alias is not an error for readers: the index is
 * created (empty) and the caller gets an empty result. Outages become 503 SEARCH_UNAVAILABLE.
 */
@Component
public class SearchGateway {

    private static final Logger log = LoggerFactory.getLogger(SearchGateway.class);

    private final ElasticsearchClient client;
    private final IndexManager indexManager;

    public SearchGateway(ElasticsearchClient client, IndexManager indexManager) {
        this.client = client;
        this.indexManager = indexManager;
    }

    /** Runs a search on the alias; empty when the index does not exist (yet). */
    public <T> Optional<SearchResponse<T>> search(IndexDefinition definition,
                                                  UnaryOperator<SearchRequest.Builder> request, Class<T> type) {
        try {
            return Optional.of(client.search(s -> request.apply(s.index(definition.alias())), type));
        } catch (ElasticsearchException e) {
            return handleMissingIndex(definition, e);
        } catch (IOException e) {
            throw new SearchUnavailableException(e);
        }
    }

    /** The document stored under {@code id}, if any. */
    public <T> Optional<T> get(IndexDefinition definition, String id, Class<T> type) {
        try {
            GetResponse<T> response = client.get(g -> g.index(definition.alias()).id(id), type);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        } catch (ElasticsearchException e) {
            return handleMissingIndex(definition, e);
        } catch (IOException e) {
            throw new SearchUnavailableException(e);
        }
    }

    /**
     * The documents stored under {@code ids}, fetched with ONE multi-get on the alias (real-time: no refresh
     * needed), keyed by id; ids without a document are simply absent. Empty when the index does not exist (yet):
     * it is created on the way, like for the other reads.
     *
     * @param sourceFields the {@code _source} fields to return
     */
    public <T> Optional<Map<String, T>> multiGet(IndexDefinition definition, List<String> ids,
                                                 List<String> sourceFields, Class<T> type) {
        if (ids.isEmpty()) {
            return Optional.of(Map.of());
        }
        MgetResponse<T> response;
        try {
            response = client.mget(m -> m.index(definition.alias()).ids(ids).sourceIncludes(sourceFields), type);
        } catch (ElasticsearchException e) {
            return handleMissingIndex(definition, e);
        } catch (IOException e) {
            throw new SearchUnavailableException(e);
        }
        Map<String, T> found = new LinkedHashMap<>();
        for (MultiGetResponseItem<T> item : response.docs()) {
            if (item.isFailure()) {
                // A multi-get reports a missing index per document (HTTP 200), not as a request error.
                ErrorCause error = item.failure().error();
                if (error != null && ElasticsearchErrors.INDEX_NOT_FOUND.equals(error.type())) {
                    log.warn("Search alias '{}' is missing; creating it", definition.alias());
                    indexManager.ensureQuietly(definition);
                    return Optional.empty();
                }
                throw new SearchUnavailableException(new IllegalStateException("multi-get " + definition.alias()
                        + "/" + item.failure().id() + " failed: " + (error == null ? "unknown error" : error.reason())));
            }
            GetResult<T> result = item.result();
            if (result.found() && result.source() != null) {
                found.put(result.id(), result.source());
            }
        }
        return Optional.of(found);
    }

    /** Converts inner hits and other raw JSON with the client's own mapper. */
    public <T> T convert(JsonData data, Class<T> type) {
        JsonpMapper mapper = client._jsonpMapper();
        return data.to(type, mapper);
    }

    private <R> Optional<R> handleMissingIndex(IndexDefinition definition, ElasticsearchException e) {
        if (ElasticsearchErrors.isIndexNotFound(e)) {
            log.warn("Search alias '{}' is missing; creating it", definition.alias());
            indexManager.ensureQuietly(definition);
            return Optional.empty();
        }
        if (ElasticsearchErrors.isTransient(e)) {
            throw new SearchUnavailableException(e);
        }
        throw e;
    }
}
