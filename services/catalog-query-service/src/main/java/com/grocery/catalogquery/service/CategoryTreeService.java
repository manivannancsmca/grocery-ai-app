package com.grocery.catalogquery.service;

import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.grocery.catalogquery.document.CategoryDocument;
import com.grocery.catalogquery.dto.CatalogDtos.CategoryNode;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.SearchGateway;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The customer-facing category tree: active categories only, each with the visible products of its subtree. */
@Service
public class CategoryTreeService {

    /** Categories are a small admin-managed tree (≤ 3 levels); this is far above any realistic size. */
    static final int MAX_CATEGORIES = 1_000;

    private final SearchGateway gateway;

    public CategoryTreeService(SearchGateway gateway) {
        this.gateway = gateway;
    }

    public List<CategoryNode> tree() {
        List<CategoryDocument> categories = gateway.search(IndexDefinition.CATEGORIES, s -> s
                        .size(MAX_CATEGORIES)
                        .query(Queries.term("effectiveActive", true))
                        .source(src -> src.filter(f -> f.excludes("path"))),
                CategoryDocument.class)
                .map(r -> r.hits().hits().stream().map(Hit::source).filter(Objects::nonNull).toList())
                .orElse(List.of());
        if (categories.isEmpty()) {
            return List.of();
        }
        // categoryIds holds every ancestor too, so a parent's bucket counts its whole subtree.
        Map<String, Long> counts = new HashMap<>();
        gateway.search(IndexDefinition.PRODUCTS, s -> s
                                .size(0)
                                .query(Queries.term("visible", true))
                                .aggregations("byCategory", a -> a.terms(t -> t.field("categoryIds").size(MAX_CATEGORIES))),
                        JsonData.class)
                .ifPresent(r -> Queries.buckets(r, "byCategory")
                        .forEach(b -> counts.put(b.key().stringValue(), b.docCount())));

        Map<String, MutableNode> nodes = new LinkedHashMap<>();
        for (CategoryDocument category : categories) {
            nodes.put(category.categoryId(), new MutableNode(category, counts.getOrDefault(category.categoryId(), 0L)));
        }
        List<MutableNode> roots = new ArrayList<>();
        for (MutableNode node : nodes.values()) {
            String parentId = node.category.parentId();
            if (parentId == null) {
                roots.add(node);
            } else if (nodes.containsKey(parentId)) {
                nodes.get(parentId).children.add(node);
            }
            // else: the parent is not visible (or not indexed yet) — the subtree is hidden with it
        }
        return toNodes(roots);
    }

    private static List<CategoryNode> toNodes(List<MutableNode> nodes) {
        return nodes.stream()
                .sorted(Comparator.comparing((MutableNode n) -> n.category.sortOrder(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(n -> n.category.name(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(n -> new CategoryNode(n.category.categoryId(), n.category.name(), n.category.description(),
                        n.category.imageUrl(), n.productCount, toNodes(n.children)))
                .toList();
    }

    private static final class MutableNode {
        final CategoryDocument category;
        final long productCount;
        final List<MutableNode> children = new ArrayList<>();

        MutableNode(CategoryDocument category, long productCount) {
            this.category = category;
            this.productCount = productCount;
        }
    }
}
