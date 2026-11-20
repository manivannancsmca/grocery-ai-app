package com.grocery.catalogquery.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.common.kafka.TransientFailures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Elasticsearch down: writes must be retried (transient), never dead-lettered; startup must not fail. */
class SearchDocumentWriterOutageTest {

    /** Nothing listens on port 1. */
    private final Rest5Client restClient = Rest5Client.builder(URI.create("http://127.0.0.1:1")).build();
    private final ElasticsearchClient client =
            new ElasticsearchClient(new Rest5ClientTransport(restClient, new Jackson3JsonpMapper()));
    private final IndexManager indexManager = new IndexManager(client, JsonMapper.builder().build());
    private final SearchDocumentWriter writer = new SearchDocumentWriter(client, indexManager);

    @AfterEach
    void close() throws IOException {
        restClient.close();
    }

    @Test
    void writesDuringAnOutageAreTransientFailures() {
        assertThatThrownBy(() -> writer.index(IndexDefinition.PRODUCTS, "p-1", 3, Map.of("name", "Tomato")))
                .isInstanceOf(TransientEventProcessingException.class)
                .satisfies(e -> assertThat(TransientFailures.isTransient(e)).isTrue());
        assertThatThrownBy(() -> writer.delete(IndexDefinition.CATEGORIES, "c-1", 3))
                .isInstanceOf(TransientEventProcessingException.class);
        assertThatThrownBy(() -> writer.merge(IndexDefinition.PRODUCTS, "p-1", "priceVersion", 3,
                List.of("sellingPrice", "priceVersion"), Map.of("sellingPrice", 49, "priceVersion", 3)))
                .isInstanceOf(TransientEventProcessingException.class)
                .satisfies(e -> assertThat(TransientFailures.isTransient(e)).isTrue());
    }

    @Test
    void startupDoesNotFailWhenElasticsearchIsDown() {
        indexManager.createMissingIndexesAtStartup();
        assertThat(indexManager.ensureQuietly(IndexDefinition.PRODUCTS)).isFalse();
    }

    @Test
    void indexDefinitionsGetTheSynonymsInlined() {
        assertThat(indexManager.synonyms()).hasSizeGreaterThanOrEqualTo(25)
                .contains("curd, yogurt, yoghurt, dahi")
                .noneMatch(line -> line.startsWith("#"));
        String products = indexManager.definitionJson(IndexDefinition.PRODUCTS);
        assertThat(products).contains("\"lady finger, ladies finger, okra, bhindi\"").contains("\"strict\"");
    }
}
