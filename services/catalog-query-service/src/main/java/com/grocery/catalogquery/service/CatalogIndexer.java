package com.grocery.catalogquery.service;

import com.grocery.catalogquery.document.CategoryDocument;
import com.grocery.catalogquery.document.IdName;
import com.grocery.catalogquery.document.ProductDocument;
import com.grocery.catalogquery.event.EventPayloads.CategoryChanged;
import com.grocery.catalogquery.event.EventPayloads.ProductUpserted;
import com.grocery.catalogquery.event.EventPayloads.Ref;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.SearchDocumentWriter;
import com.grocery.common.kafka.InvalidEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** catalog.events snapshots → the {@code products} and {@code categories} indexes. */
@Service
public class CatalogIndexer {

    static final String PUBLISHED = "PUBLISHED";
    static final String DRAFT = "DRAFT";

    private static final Logger log = LoggerFactory.getLogger(CatalogIndexer.class);

    private final SearchDocumentWriter writer;

    public CatalogIndexer(SearchDocumentWriter writer) {
        this.writer = writer;
    }

    /**
     * Indexes every product state (UNPUBLISHED / ARCHIVED as invisible, so their versions are remembered), except
     * a DRAFT that has no document yet: drafts are not searchable and need no document. (A draft that already got
     * a price or stock has a hidden placeholder document; the snapshot then completes it, still invisible.)
     */
    public void onProductUpserted(ProductUpserted product) {
        if (blank(product.productId()) || product.productVersion() == null || blank(product.status())) {
            throw new InvalidEventException("ProductUpserted without productId, productVersion or status");
        }
        if (DRAFT.equals(product.status()) && !writer.exists(IndexDefinition.PRODUCTS, product.productId())) {
            log.debug("Product {} v{} is a draft: not indexed", product.productId(), product.productVersion());
            return;
        }
        // Only the catalog part of the document: price and stock belong to their own events (PriceAndStockIndexer),
        // the rating to review.events (RatingIndexer).
        writer.merge(IndexDefinition.PRODUCTS, product.productId(), ProductDocument.PRODUCT_VERSION,
                product.productVersion(), ProductDocument.CATALOG_FIELDS, toDocument(product));
    }

    /** Indexes the category, or deletes it ({@code deleted=true}) — both versioned by categoryVersion. */
    public void onCategoryChanged(CategoryChanged category) {
        if (blank(category.categoryId()) || category.categoryVersion() == null) {
            throw new InvalidEventException("CategoryChanged without categoryId or categoryVersion");
        }
        if (Boolean.TRUE.equals(category.deleted())) {
            writer.delete(IndexDefinition.CATEGORIES, category.categoryId(), category.categoryVersion());
        } else {
            writer.index(IndexDefinition.CATEGORIES, category.categoryId(), category.categoryVersion(),
                    toDocument(category));
        }
    }

    static ProductDocument toDocument(ProductUpserted p) {
        List<IdName> path = refs(p.categoryPath());
        IdName leaf = p.category() != null && !blank(p.category().id())
                ? new IdName(p.category().id(), p.category().name())
                : path.isEmpty() ? null : path.getLast();
        Set<String> categoryIds = new LinkedHashSet<>();
        Set<String> categoryNames = new LinkedHashSet<>();
        for (IdName entry : path) {
            categoryIds.add(entry.id());
            addIfPresent(categoryNames, entry.name());
        }
        if (leaf != null) {
            categoryIds.add(leaf.id());
            addIfPresent(categoryNames, leaf.name());
        }
        IdName brand = p.brand() != null && !blank(p.brand().id()) ? new IdName(p.brand().id(), p.brand().name()) : null;
        List<String> images = p.imageUrls() == null ? List.of() : p.imageUrls().stream().filter(Objects::nonNull).toList();
        boolean visible = PUBLISHED.equals(p.status())
                && Boolean.TRUE.equals(p.vendorActive())
                && Boolean.TRUE.equals(p.categoryActive());
        return new ProductDocument(
                p.productId(),
                p.vendorId(),
                p.vendorName(),
                p.name(),
                p.description(),
                brand == null ? null : brand.id(),
                brand == null ? null : brand.name(),
                brand == null ? null : brand.facetKey(),
                leaf == null ? null : leaf.id(),
                List.copyOf(categoryIds),
                List.copyOf(categoryNames),
                leaf == null ? null : leaf.facetKey(),
                path,
                plain(p.unitValue()),
                p.unitOfMeasure(),
                p.unitLabel(),
                p.sku(),
                p.barcode(),
                p.foodType(),
                p.tags() == null ? List.of() : p.tags().stream().filter(Objects::nonNull).toList(),
                p.attributes() == null ? List.of() : p.attributes().stream().filter(Objects::nonNull)
                        .map(a -> new ProductDocument.Attribute(a.name(), a.value())).toList(),
                images,
                images.isEmpty() ? null : images.getFirst(),
                p.status(),
                visible,
                p.publishedAt(),
                p.updatedAt(),
                p.productVersion(),
                null, null, null, null, null,       // price part: written by PriceChanged
                null, null,                         // stock part: written by ProductAvailabilityChanged
                null, null, null);                  // rating part: written by RatingSummaryUpdated
    }

    static CategoryDocument toDocument(CategoryChanged c) {
        List<IdName> path = refs(c.path());
        List<String> pathNames = new ArrayList<>();
        path.forEach(entry -> addIfPresent(pathNames, entry.name()));
        if (pathNames.isEmpty() && c.name() != null) {
            pathNames.add(c.name());
        }
        return new CategoryDocument(
                c.categoryId(),
                c.parentId(),
                c.name(),
                c.description(),
                c.imageUrl(),
                c.sortOrder() == null ? 0 : c.sortOrder(),
                c.depth(),
                path,
                pathNames,
                Boolean.TRUE.equals(c.active()),
                Boolean.TRUE.equals(c.effectiveActive()),
                c.categoryVersion());
    }

    /**
     * The pack size as catalog-command writes it (500, 1.5, 0.125: trailing zeros stripped, never in exponent form).
     * Avro carries it as decimal(10,3) (ADR-0011), so it arrives as 500.000; indexed as-is it would be stored and
     * served as 500.0 instead of 500.
     */
    static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static List<IdName> refs(List<Ref> refs) {
        if (refs == null) {
            return List.of();
        }
        return refs.stream()
                .filter(ref -> ref != null && !blank(ref.id()))
                .map(ref -> new IdName(ref.id(), ref.name()))
                .toList();
    }

    private static void addIfPresent(Collection<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
