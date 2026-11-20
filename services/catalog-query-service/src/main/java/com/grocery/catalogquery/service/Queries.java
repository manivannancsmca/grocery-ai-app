package com.grocery.catalogquery.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.mapping.FieldType;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;

/** Small builders shared by the search services. */
final class Queries {

    /** Index-time analyzer: no synonyms (fuzzy queries must not be combined with synonym graphs). */
    static final String TEXT_ANALYZER = "product_text";
    /** Search-time analyzer: {@link #TEXT_ANALYZER} + the grocery synonym graph. */
    static final String SEARCH_ANALYZER = "product_search";
    static final String FUZZY = "AUTO";
    /** Upper bound for free-text input; longer queries are cut. */
    static final int MAX_QUERY_LENGTH = 200;

    private Queries() {
    }

    static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    static Query term(String field, boolean value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    static Query terms(String field, Collection<String> values) {
        List<FieldValue> fieldValues = values.stream().map(FieldValue::of).toList();
        return Query.of(q -> q.terms(t -> t.field(field).terms(v -> v.value(fieldValues))));
    }

    /** {@code gte ≤ field ≤ lte}; either bound may be null (open). Documents without the field never match. */
    static Query range(String field, Double gte, Double lte) {
        return Query.of(q -> q.range(r -> r.number(n -> {
            n.field(field);
            if (gte != null) {
                n.gte(gte);
            }
            if (lte != null) {
                n.lte(lte);
            }
            return n;
        })));
    }

    /**
     * Sort by a numeric field whose value may be absent: documents without it come last in either direction. The
     * unmapped type keeps the sort working on an index whose mapping does not have the field yet.
     */
    static SortOptions byFieldMissingLast(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order).missing("_last")
                .unmappedType(FieldType.Double)));
    }

    static SortOptions byScore() {
        return SortOptions.of(s -> s.score(score -> score.order(SortOrder.Desc)));
    }

    static SortOptions byField(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    /** A stored rating as the API shows it: 2 decimals, or null while unrated (Phase 12). */
    static BigDecimal rating(BigDecimal stored) {
        return stored == null ? null : stored.setScale(2, RoundingMode.HALF_UP);
    }

    /** Trimmed free text, cut to {@link #MAX_QUERY_LENGTH}; null when blank. */
    static String normalizeText(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.strip();
        return trimmed.length() > MAX_QUERY_LENGTH ? trimmed.substring(0, MAX_QUERY_LENGTH) : trimmed;
    }

    static List<StringTermsBucket> buckets(SearchResponse<?> response, String aggregation) {
        Aggregate aggregate = response.aggregations() == null ? null : response.aggregations().get(aggregation);
        if (aggregate == null || !aggregate.isSterms() || !aggregate.sterms().buckets().isArray()) {
            return List.of();
        }
        return aggregate.sterms().buckets().array();
    }

    static long total(SearchResponse<?> response) {
        TotalHits total = response.hits().total();
        return total == null ? response.hits().hits().size() : total.value();
    }
}
