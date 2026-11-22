package com.grocery.catalogquery.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.grocery.catalogquery.document.CategoryDocument;
import com.grocery.catalogquery.document.IdName;
import com.grocery.catalogquery.document.ProductDocument;
import com.grocery.catalogquery.dto.CatalogDtos.CategorySuggestion;
import com.grocery.catalogquery.dto.CatalogDtos.FacetEntry;
import com.grocery.catalogquery.dto.CatalogDtos.FacetValue;
import com.grocery.catalogquery.dto.CatalogDtos.NameValue;
import com.grocery.catalogquery.dto.CatalogDtos.Price;
import com.grocery.catalogquery.dto.CatalogDtos.ProductCard;
import com.grocery.catalogquery.dto.CatalogDtos.ProductDetail;
import com.grocery.catalogquery.dto.CatalogDtos.ProductFacets;
import com.grocery.catalogquery.dto.CatalogDtos.ProductLookupResponse;
import com.grocery.catalogquery.dto.CatalogDtos.ProductSearchResponse;
import com.grocery.catalogquery.dto.CatalogDtos.ProductSuggestion;
import com.grocery.catalogquery.dto.CatalogDtos.Ref;
import com.grocery.catalogquery.dto.CatalogDtos.SuggestResponse;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.SearchDocumentWriter;
import com.grocery.catalogquery.index.SearchGateway;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.NotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.grocery.catalogquery.service.Queries.FUZZY;
import static com.grocery.catalogquery.service.Queries.SEARCH_ANALYZER;
import static com.grocery.catalogquery.service.Queries.TEXT_ANALYZER;

/** Product search, suggestions, the product page and the internal lookup — visible products only. */
@Service
public class ProductSearchService {

    public enum Sort { RELEVANCE, NEWEST, NAME_ASC, PRICE_ASC, PRICE_DESC, RATING }

    public enum FoodType { VEG, NON_VEG, EGG }

    /** Most {@code vendorId} values one search may name (the nearby stores of a customer). */
    public static final int MAX_VENDOR_IDS = 50;

    /**
     * Validated search input; {@code sort} null = relevance with a query, otherwise newest.
     *
     * @param vendorIds   products of any of these vendors; empty (or null) = every vendor
     * @param inStockOnly only products that can be bought right now
     * @param minPrice    lower bound of the selling price (inclusive), or null
     * @param maxPrice    upper bound of the selling price (inclusive), or null
     */
    public record Criteria(String q, String categoryId, String brandId, List<String> vendorIds, FoodType foodType,
                           boolean inStockOnly, BigDecimal minPrice, BigDecimal maxPrice,
                           Sort sort, SearchPage page) {

        public Criteria {
            vendorIds = vendorIds == null ? List.of() : List.copyOf(vendorIds);
        }
    }

    /**
     * The {@code vendorId} filter of a search (repeatable): at most {@link #MAX_VENDOR_IDS} values, else 400
     * INVALID_PARAMETER. Null entries (an empty {@code vendorId=}) are ignored; duplicates are kept once.
     */
    public static List<String> vendorIds(List<UUID> vendorIds) {
        if (vendorIds == null) {
            return List.of();
        }
        if (vendorIds.size() > MAX_VENDOR_IDS) {
            throw new BadRequestException("INVALID_PARAMETER", "at most " + MAX_VENDOR_IDS + " vendorId values");
        }
        return vendorIds.stream().filter(Objects::nonNull).map(UUID::toString).distinct().toList();
    }

    /** Checks the price range of a search: both bounds ≥ 0 and min ≤ max, else 400 INVALID_PARAMETER. */
    public static void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && minPrice.signum() < 0) {
            throw new BadRequestException("INVALID_PARAMETER", "minPrice must not be negative");
        }
        if (maxPrice != null && maxPrice.signum() < 0) {
            throw new BadRequestException("INVALID_PARAMETER", "maxPrice must not be negative");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BadRequestException("INVALID_PARAMETER", "minPrice must not be greater than maxPrice");
        }
    }

    static final int SUGGEST_PRODUCTS = 8;
    static final int SUGGEST_CATEGORIES = 4;
    static final int MIN_SUGGEST_LENGTH = 2;
    static final int FACET_SIZE = 20;

    /** The aggregations behind {@code distinctProducts}: different names, and the matches without the description. */
    static final String DISTINCT = "distinctProducts";
    static final String NAMED = "namedMatches";
    /** The name as one keyword (lower case): the same product sold by several stores has one. */
    static final String NAME_KEYWORD = "name.keyword";

    static final String SELLING_PRICE = "sellingPrice";
    static final String IN_STOCK = "inStock";
    static final String RATING_FIELD = "rating";
    static final String RATING_COUNT_FIELD = "ratingCount";
    /** Well above the highest possible price (1,000,000.00). */
    static final BigDecimal PRICE_CEILING = new BigDecimal("1000000000");

    /** Longest {@code shortDescription} of a card; descriptions may have 2,000 characters (the detail has them all). */
    static final int SHORT_DESCRIPTION_LENGTH = 300;

    private static final List<String> CARD_FIELDS = List.of("productId", "name", "unitLabel", "foodType",
            "primaryImageUrl", "brandId", "brandName", "categoryId", "categoryFacet", "vendorId", "vendorName",
            "mrp", SELLING_PRICE, "currency", "discountPercent", IN_STOCK, RATING_FIELD, RATING_COUNT_FIELD,
            "description");
    /** The card plus the flag that decides whether the lookup may return it. */
    private static final List<String> LOOKUP_FIELDS =
            Stream.concat(CARD_FIELDS.stream(), Stream.of(SearchDocumentWriter.VISIBLE)).toList();

    private final SearchGateway gateway;

    public ProductSearchService(SearchGateway gateway) {
        this.gateway = gateway;
    }

    public ProductSearchResponse search(Criteria criteria) {
        String q = Queries.normalizeText(criteria.q());
        Query query = Query.of(root -> root.bool(b -> {
            b.filter(Queries.term("visible", true));
            if (criteria.categoryId() != null) {
                b.filter(Queries.term("categoryIds", criteria.categoryId()));
            }
            if (criteria.brandId() != null) {
                b.filter(Queries.term("brandId", criteria.brandId()));
            }
            if (!criteria.vendorIds().isEmpty()) {
                b.filter(Queries.terms("vendorId", criteria.vendorIds()));
            }
            if (criteria.foodType() != null) {
                b.filter(Queries.term("foodType", criteria.foodType().name()));
            }
            if (criteria.inStockOnly()) {
                b.filter(Queries.term(IN_STOCK, true));
            }
            if (criteria.minPrice() != null || criteria.maxPrice() != null) {
                // Unpriced products have no sellingPrice field, so a price filter never matches them.
                b.filter(Queries.range(SELLING_PRICE, bound(criteria.minPrice()), bound(criteria.maxPrice())));
            }
            if (q != null) {
                b.must(textQuery(q));
            }
            return b;
        }));
        SearchPage page = criteria.page();
        Optional<SearchResponse<ProductDocument>> found = gateway.search(IndexDefinition.PRODUCTS, s -> {
            s.query(query)
                    .from(page.from())
                    .size(page.size())
                    .trackTotalHits(t -> t.enabled(true))
                    .sort(sortFor(criteria.sort(), q))
                    .source(src -> src.filter(f -> f.includes(CARD_FIELDS)))
                    .aggregations("categories", a -> a.terms(t -> t.field("categoryFacet").size(FACET_SIZE)))
                    .aggregations("brands", a -> a.terms(t -> t.field("brandFacet").size(FACET_SIZE)))
                    .aggregations("foodTypes", a -> a.terms(t -> t.field("foodType").size(FoodType.values().length)))
                    .aggregations(DISTINCT, a -> a.cardinality(c -> c.field(NAME_KEYWORD)));
            if (q != null) {
                // the different products among the matches by name, brand, category or tags
                s.aggregations(NAMED, a -> a.filter(textQuery(q, false))
                        .aggregations(DISTINCT, d -> d.cardinality(c -> c.field(NAME_KEYWORD))));
            }
            return s;
        }, ProductDocument.class);
        if (found.isEmpty()) {
            return new ProductSearchResponse(List.of(), page.page(), page.size(), 0, 0, ProductFacets.empty(), 0);
        }
        SearchResponse<ProductDocument> response = found.get();
        List<ProductCard> cards = response.hits().hits().stream()
                .map(Hit::source).filter(Objects::nonNull).map(ProductSearchService::card).toList();
        long total = Queries.total(response);
        ProductFacets facets = new ProductFacets(
                idNameFacet(response, "categories"),
                idNameFacet(response, "brands"),
                Queries.buckets(response, "foodTypes").stream()
                        .map(b -> new FacetValue(b.key().stringValue(), b.docCount())).toList());
        return new ProductSearchResponse(cards, page.page(), page.size(), total, page.totalPages(total), facets,
                distinctProducts(response));
    }

    /**
     * Different product names among the matches by name, brand, category or tags when there are any (the {@value NAMED}
     * filter), else among every match: description-only matches ("rice" → Dog Food) count only as a last resort.
     */
    static long distinctProducts(SearchResponse<?> response) {
        Map<String, Aggregate> aggregations = response.aggregations();
        if (aggregations == null) {
            return 0;
        }
        Aggregate named = aggregations.get(NAMED);
        if (named != null && named.isFilter() && named.filter().docCount() > 0) {
            return cardinality(named.filter().aggregations().get(DISTINCT));
        }
        return cardinality(aggregations.get(DISTINCT));
    }

    private static long cardinality(Aggregate aggregate) {
        return aggregate != null && aggregate.isCardinality() ? aggregate.cardinality().value() : 0;
    }

    /** Prefix matches ("as you type") for products and categories. */
    public SuggestResponse suggest(String rawQuery) {
        String q = Queries.normalizeText(rawQuery);
        if (q == null || q.length() < MIN_SUGGEST_LENGTH) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "q must have at least " + MIN_SUGGEST_LENGTH + " characters");
        }
        List<ProductSuggestion> products = gateway.search(IndexDefinition.PRODUCTS, s -> s
                        .size(SUGGEST_PRODUCTS)
                        .query(prefixQuery(q, "visible"))
                        .source(src -> src.filter(f -> f.includes(
                                "productId", "name", "brandName", "unitLabel", "primaryImageUrl"))),
                ProductDocument.class)
                .map(r -> r.hits().hits().stream().map(Hit::source).filter(Objects::nonNull)
                        .map(d -> new ProductSuggestion(d.productId(), d.name(), d.brandName(), d.unitLabel(),
                                d.primaryImageUrl()))
                        .toList())
                .orElse(List.of());
        List<CategorySuggestion> categories = gateway.search(IndexDefinition.CATEGORIES, s -> s
                        .size(SUGGEST_CATEGORIES)
                        .query(prefixQuery(q, "effectiveActive"))
                        .source(src -> src.filter(f -> f.includes("categoryId", "name", "pathNames"))),
                CategoryDocument.class)
                .map(r -> r.hits().hits().stream().map(Hit::source).filter(Objects::nonNull)
                        .map(d -> new CategorySuggestion(d.categoryId(), d.name(),
                                d.pathNames() == null ? List.of() : d.pathNames()))
                        .toList())
                .orElse(List.of());
        return new SuggestResponse(products, categories);
    }

    public ProductDetail detail(String productId) {
        ProductDocument d = gateway.get(IndexDefinition.PRODUCTS, productId, ProductDocument.class)
                .filter(doc -> Boolean.TRUE.equals(doc.visible()))
                .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product " + productId + " was not found"));
        ProductCard card = card(d);
        return new ProductDetail(card.id(), card.name(), card.unitLabel(), card.foodType(), card.primaryImageUrl(),
                card.brand(), card.category(), card.vendor(), card.price(), card.inStock(),
                card.rating(), card.ratingCount(),
                d.description(), d.unitValue(), d.unitOfMeasure(),
                d.sku(), d.barcode(),
                d.tags() == null ? List.of() : d.tags(),
                d.attributes() == null ? List.of()
                        : d.attributes().stream().map(a -> new NameValue(a.name(), a.value())).toList(),
                d.imageUrls() == null ? List.of() : d.imageUrls(),
                d.categoryPath() == null ? List.of()
                        : d.categoryPath().stream().map(p -> new Ref(p.id(), p.name())).toList(),
                d.publishedAt(), d.updatedAt());
    }

    /**
     * The cart's product lookup (internal): one multi-get through the alias. Only products that are visible by the
     * Phase 3 rule (published, vendor active, whole category path active — the {@code visible} flag) become cards,
     * exactly as search shows them; unknown ids, hidden products and price/stock placeholders are missing. A missing
     * index means everything is missing (the index is created on the way).
     *
     * @param productIds 1–100 ids (validated by the controller); duplicates are looked up and returned once
     */
    public ProductLookupResponse lookup(List<UUID> productIds) {
        List<String> ids = productIds.stream().map(UUID::toString).distinct().toList();
        Map<String, ProductDocument> found = gateway.multiGet(IndexDefinition.PRODUCTS, ids, LOOKUP_FIELDS,
                        ProductDocument.class)
                .orElse(Map.of());
        List<ProductCard> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            ProductDocument d = found.get(id);
            if (d != null && Boolean.TRUE.equals(d.visible())) {
                items.add(card(d));
            } else {
                missing.add(id);
            }
        }
        return new ProductLookupResponse(items, missing);
    }

    /**
     * Name matches rank above brand, category, tags and description. Three alternatives, any of which may match:
     * synonym-aware exact words, typo-tolerant words (fuzziness cannot be combined with synonym graphs, so this
     * clause uses the plain analyzer), and a prefix of the name while typing.
     */
    static Query textQuery(String q) {
        return textQuery(q, true);
    }

    /** The text query, with or without the description (without: what counts as a product the words name). */
    static Query textQuery(String q, boolean descriptions) {
        List<String> fields = descriptions
                ? List.of("name^4", "brandName^2", "categoryNames^1.5", "tags.text^1.5", "description^0.5")
                : List.of("name^4", "brandName^2", "categoryNames^1.5", "tags.text^1.5");
        return Query.of(root -> root.bool(b -> b
                .should(s -> s.multiMatch(m -> m
                        .query(q)
                        .type(TextQueryType.BestFields)
                        .fields(fields)
                        .analyzer(SEARCH_ANALYZER)
                        .operator(Operator.And)))
                .should(s -> s.multiMatch(m -> m
                        .query(q)
                        .type(TextQueryType.BestFields)
                        .fields("name^3", "brandName")
                        .fuzziness(FUZZY)
                        .prefixLength(1)
                        .analyzer(TEXT_ANALYZER)
                        .operator(Operator.And)))
                .should(s -> s.matchBoolPrefix(m -> m
                        .field("name.sayt")
                        .query(q)
                        .operator(Operator.And)))
                .minimumShouldMatch("1")));
    }

    /** search_as_you_type prefix query restricted to documents where {@code flag} is true. */
    private static Query prefixQuery(String q, String flag) {
        return Query.of(root -> root.bool(b -> b
                .filter(Queries.term(flag, true))
                .must(m -> m.multiMatch(mm -> mm
                        .query(q)
                        .type(TextQueryType.BoolPrefix)
                        .fields("name.sayt", "name.sayt._2gram", "name.sayt._3gram")
                        .fuzziness(FUZZY)
                        .operator(Operator.And)))));
    }

    private static List<SortOptions> sortFor(Sort requested, String q) {
        Sort sort = requested == null ? (q != null ? Sort.RELEVANCE : Sort.NEWEST) : requested;
        if (sort == Sort.RELEVANCE && q == null) {
            sort = Sort.NEWEST;     // nothing to rank by
        }
        List<SortOptions> options = new ArrayList<>();
        switch (sort) {
            case RELEVANCE -> {
                options.add(Queries.byScore());
                options.add(Queries.byField("publishedAt", SortOrder.Desc));
            }
            case NEWEST -> options.add(Queries.byField("publishedAt", SortOrder.Desc));
            case NAME_ASC -> options.add(Queries.byField("name.keyword", SortOrder.Asc));
            // Products without a price come last in both directions.
            case PRICE_ASC -> options.add(Queries.byFieldMissingLast(SELLING_PRICE, SortOrder.Asc));
            case PRICE_DESC -> options.add(Queries.byFieldMissingLast(SELLING_PRICE, SortOrder.Desc));
            // Phase 12: highest rated first, unrated last; among equal ratings the most rated, then the newest.
            case RATING -> {
                options.add(Queries.byFieldMissingLast(RATING_FIELD, SortOrder.Desc));
                options.add(Queries.byFieldMissingLast(RATING_COUNT_FIELD, SortOrder.Desc));
                options.add(Queries.byField("publishedAt", SortOrder.Desc));
            }
        }
        options.add(Queries.byField("productId", SortOrder.Asc));      // stable paging
        return options;
    }

    private static List<FacetEntry> idNameFacet(SearchResponse<?> response, String aggregation) {
        return Queries.buckets(response, aggregation).stream()
                .map(bucket -> {
                    IdName idName = IdName.fromFacetKey(bucket.key().stringValue());
                    return idName == null ? null : new FacetEntry(idName.id(), idName.name(), bucket.docCount());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    static ProductCard card(ProductDocument d) {
        IdName leaf = IdName.fromFacetKey(d.categoryFacet());
        Ref category = leaf != null ? new Ref(leaf.id(), leaf.name())
                : d.categoryId() == null ? null : new Ref(d.categoryId(), null);
        return new ProductCard(d.productId(), d.name(), d.unitLabel(), d.foodType(), d.primaryImageUrl(),
                d.brandId() == null ? null : new Ref(d.brandId(), d.brandName()),
                category,
                new Ref(d.vendorId(), d.vendorName()),
                price(d),
                Boolean.TRUE.equals(d.inStock()),
                Queries.rating(d.rating()),
                d.ratingCount() == null ? 0 : d.ratingCount(),
                shortDescription(d.description()));
    }

    /**
     * The description in one line, at most {@link #SHORT_DESCRIPTION_LENGTH} characters: a longer one is cut at the
     * last space that fits and ends in "…"; null when there is none.
     */
    static String shortDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String text = description.strip().replaceAll("\\s+", " ");
        if (text.length() <= SHORT_DESCRIPTION_LENGTH) {
            return text;
        }
        int space = text.lastIndexOf(' ', SHORT_DESCRIPTION_LENGTH - 1);
        return text.substring(0, space > 0 ? space : SHORT_DESCRIPTION_LENGTH - 1).stripTrailing() + "…";
    }

    /** The live price, or null while the product has none. Amounts always carry 2 decimals. */
    static Price price(ProductDocument d) {
        if (d.mrp() == null || d.sellingPrice() == null) {
            return null;
        }
        return new Price(money(d.mrp()), money(d.sellingPrice()),
                d.currency() == null ? PriceAndStockIndexer.DEFAULT_CURRENCY : d.currency(),
                d.discountPercent() == null ? 0 : d.discountPercent());
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * A price bound for the range query. Prices never exceed 1,000,000.00, so anything above {@link #PRICE_CEILING}
     * is the same as the ceiling (and an absurd value such as 1e400 cannot become an infinite JSON number).
     */
    private static Double bound(BigDecimal price) {
        if (price == null) {
            return null;
        }
        return price.compareTo(PRICE_CEILING) > 0 ? PRICE_CEILING.doubleValue() : price.doubleValue();
    }
}
