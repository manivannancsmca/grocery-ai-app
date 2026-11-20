package com.grocery.catalogquery.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.grocery.catalogquery.index.IndexManager;
import com.grocery.catalogquery.index.SearchGateway;
import com.grocery.catalogquery.index.SearchUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Elasticsearch down: the internal product lookup answers 503 SEARCH_UNAVAILABLE like the public reads. */
class ProductLookupOutageTest {

    /** Nothing listens on port 1. */
    private final Rest5Client restClient = Rest5Client.builder(URI.create("http://127.0.0.1:1")).build();
    private final ElasticsearchClient client =
            new ElasticsearchClient(new Rest5ClientTransport(restClient, new Jackson3JsonpMapper()));
    private final ProductSearchService products = new ProductSearchService(
            new SearchGateway(client, new IndexManager(client, JsonMapper.builder().build())));

    @AfterEach
    void close() throws IOException {
        restClient.close();
    }

    @Test
    void aLookupDuringAnOutageIsSearchUnavailable() {
        assertThatThrownBy(() -> products.lookup(List.of(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOfSatisfying(SearchUnavailableException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.errorCode()).isEqualTo("SEARCH_UNAVAILABLE");
                });
    }
}
