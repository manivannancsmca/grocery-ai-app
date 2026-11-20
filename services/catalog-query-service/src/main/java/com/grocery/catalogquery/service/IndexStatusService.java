package com.grocery.catalogquery.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.grocery.catalogquery.dto.CatalogDtos.IndexStatus;
import com.grocery.catalogquery.index.ElasticsearchErrors;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.IndexManager;
import com.grocery.catalogquery.index.SearchUnavailableException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Admin view of the three aliases: which index serves each, its document count and health. */
@Service
public class IndexStatusService {

    static final String MISSING = "missing";

    private final ElasticsearchClient client;
    private final IndexManager indexManager;

    public IndexStatusService(ElasticsearchClient client, IndexManager indexManager) {
        this.client = client;
        this.indexManager = indexManager;
    }

    public List<IndexStatus> statuses() {
        List<IndexStatus> statuses = new ArrayList<>();
        for (IndexDefinition definition : List.of(IndexDefinition.PRODUCTS, IndexDefinition.RESTAURANTS,
                IndexDefinition.CATEGORIES)) {
            statuses.add(status(definition));
        }
        return statuses;
    }

    private IndexStatus status(IndexDefinition definition) {
        String alias = definition.alias();
        try {
            indexManager.recheck(definition);      // an admin looking means "make sure it is there"
            if (!client.indices().existsAlias(a -> a.name(alias)).value()) {
                return new IndexStatus(alias, null, 0, MISSING);
            }
            String index = client.indices().getAlias(a -> a.name(alias)).aliases().keySet().stream()
                    .sorted().findFirst().orElse(null);
            if (index == null) {
                return new IndexStatus(alias, null, 0, MISSING);
            }
            long documents = client.count(c -> c.index(alias)).count();
            String health = client.cluster().health(h -> h.index(index)).status().jsonValue();
            return new IndexStatus(alias, index, documents, health);
        } catch (ElasticsearchException e) {
            if (ElasticsearchErrors.isIndexNotFound(e)) {
                return new IndexStatus(alias, null, 0, MISSING);
            }
            throw new SearchUnavailableException(e);
        } catch (IOException e) {
            throw new SearchUnavailableException(e);
        }
    }
}
