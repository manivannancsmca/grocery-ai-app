package com.grocery.catalogquery.service;

import co.elastic.clients.elasticsearch._types.DistanceUnit;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.GeoDistanceType;
import co.elastic.clients.elasticsearch._types.GeoLocation;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.ChildScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.InnerHitsResult;
import com.grocery.catalogquery.config.CatalogProperties;
import com.grocery.catalogquery.document.RestaurantDocument;
import com.grocery.catalogquery.document.RestaurantDocument.SearchableMenuItem;
import com.grocery.catalogquery.dto.CatalogDtos.Address;
import com.grocery.catalogquery.dto.CatalogDtos.FacetValue;
import com.grocery.catalogquery.dto.CatalogDtos.MatchingItem;
import com.grocery.catalogquery.dto.CatalogDtos.MenuCategoryView;
import com.grocery.catalogquery.dto.CatalogDtos.MenuItemView;
import com.grocery.catalogquery.dto.CatalogDtos.OpeningHoursView;
import com.grocery.catalogquery.dto.CatalogDtos.RestaurantCard;
import com.grocery.catalogquery.dto.CatalogDtos.RestaurantDetail;
import com.grocery.catalogquery.dto.CatalogDtos.RestaurantFacets;
import com.grocery.catalogquery.dto.CatalogDtos.RestaurantSearchResponse;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.SearchGateway;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static com.grocery.catalogquery.service.Queries.FUZZY;
import static com.grocery.catalogquery.service.Queries.SEARCH_ANALYZER;
import static com.grocery.catalogquery.service.Queries.TEXT_ANALYZER;

/** Restaurant search (near me, dishes, cuisines, open now) and the restaurant page — APPROVED restaurants only. */
@Service
public class RestaurantSearchService {

    /** Validated location; null when the search is not location based. */
    public record Location(double lat, double lng, double radiusKm) {
    }

    /** Default: relevance with a query, else distance with a location, else name. */
    public enum Sort { RATING }

    /**
     * @param minRating only restaurants rated at least this (0–5); unrated restaurants never match (Phase 12)
     * @param sort      null = the default order; RATING = highest rated first, unrated last (Phase 12)
     */
    public record Criteria(String q, Location location, List<String> cuisines, boolean pureVeg, boolean openNow,
                           Double minRating, Sort sort, SearchPage page) {
    }

    public static final double DEFAULT_RADIUS_KM = 10;
    public static final double MAX_RATING = 5;
    static final String RATING_FIELD = "rating";
    static final String RATING_COUNT_FIELD = "ratingCount";
    public static final double MAX_RADIUS_KM = 30;
    static final String MATCHING_ITEMS = "matchingItems";
    static final int MAX_MATCHING_ITEMS = 3;
    static final int CUISINE_FACET_SIZE = 30;

    private final SearchGateway gateway;
    private final Clock clock;
    private final CatalogProperties properties;

    public RestaurantSearchService(SearchGateway gateway, Clock clock, CatalogProperties properties) {
        this.gateway = gateway;
        this.clock = clock;
        this.properties = properties;
    }

    /** Both coordinates or neither; radius defaults to 10 km and is capped at 30 km. */
    public static Location location(Double lat, Double lng, Double radiusKm) {
        if (lat == null && lng == null) {
            return null;
        }
        if (lat == null || lng == null) {
            throw new BadRequestException("INVALID_PARAMETER", "lat and lng must be given together");
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new BadRequestException("INVALID_PARAMETER", "lat must be within ±90 and lng within ±180");
        }
        double radius = radiusKm == null ? DEFAULT_RADIUS_KM : radiusKm;
        if (!(radius > 0)) {
            throw new BadRequestException("INVALID_PARAMETER", "radiusKm must be greater than 0");
        }
        return new Location(lat, lng, Math.min(radius, MAX_RADIUS_KM));
    }

    /** {@code minRating}: absent, or 0–5 (else 400 INVALID_PARAMETER). */
    public static Double minRating(Double minRating) {
        if (minRating != null && (minRating.isNaN() || minRating < 0 || minRating > MAX_RATING)) {
            throw new BadRequestException("INVALID_PARAMETER", "minRating must be between 0 and 5");
        }
        return minRating;
    }

    public RestaurantSearchResponse search(Criteria criteria) {
        String q = Queries.normalizeText(criteria.q());
        Location location = criteria.location();
        ZonedDateTime now = now();
        List<String> cuisines = criteria.cuisines() == null ? List.of() : criteria.cuisines().stream()
                .filter(Objects::nonNull).map(String::strip).filter(c -> !c.isEmpty())
                .map(c -> c.toUpperCase(Locale.ROOT)).distinct().toList();

        Query query = Query.of(root -> root.bool(b -> {
            b.filter(Queries.term("visible", true));
            if (location != null) {
                b.filter(f -> f.geoDistance(g -> g
                        .field("location")
                        .distance(location.radiusKm() + "km")
                        .location(geo(location))));
            }
            if (!cuisines.isEmpty()) {
                b.filter(Queries.terms("cuisines", cuisines));
            }
            if (criteria.pureVeg()) {
                b.filter(Queries.term("pureVeg", true));
            }
            if (criteria.openNow()) {
                b.filter(openAt(now));
            }
            if (criteria.minRating() != null) {
                // Unrated restaurants have no rating field, so they never match.
                b.filter(Queries.range(RATING_FIELD, criteria.minRating(), null));
            }
            if (q != null) {
                b.must(textQuery(q));
            }
            return b;
        }));

        List<SortOptions> sort = new ArrayList<>();
        int distanceSortIndex = -1;
        if (criteria.sort() == Sort.RATING) {
            // Phase 12: highest rated first, unrated last; then the most rated; then the default order below.
            sort.add(Queries.byFieldMissingLast(RATING_FIELD, SortOrder.Desc));
            sort.add(Queries.byFieldMissingLast(RATING_COUNT_FIELD, SortOrder.Desc));
        }
        if (q != null) {
            sort.add(Queries.byScore());
        }
        if (location != null) {
            distanceSortIndex = sort.size();
            sort.add(SortOptions.of(s -> s.geoDistance(g -> g
                    .field("location")
                    .location(geo(location))
                    .order(SortOrder.Asc)
                    .unit(DistanceUnit.Kilometers)
                    .distanceType(GeoDistanceType.Arc))));
        } else if (q == null) {
            sort.add(Queries.byField("name.keyword", SortOrder.Asc));
        }
        sort.add(Queries.byField("restaurantId", SortOrder.Asc));      // stable paging

        SearchPage page = criteria.page();
        int distanceIndex = distanceSortIndex;
        Optional<SearchResponse<RestaurantDocument>> found = gateway.search(IndexDefinition.RESTAURANTS, s -> s
                        .query(query)
                        .from(page.from())
                        .size(page.size())
                        .trackTotalHits(t -> t.enabled(true))
                        .sort(sort)
                        .source(src -> src.filter(f -> f.excludes("menu", "menuItems", "address", "openingHoursDisplay")))
                        .aggregations("cuisines", a -> a.terms(t -> t.field("cuisines").size(CUISINE_FACET_SIZE))),
                RestaurantDocument.class);
        if (found.isEmpty()) {
            return new RestaurantSearchResponse(List.of(), page.page(), page.size(), 0, 0,
                    new RestaurantFacets(List.of()));
        }
        SearchResponse<RestaurantDocument> response = found.get();
        List<RestaurantCard> cards = response.hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> card(hit, distanceIndex, now, q != null))
                .toList();
        long total = Queries.total(response);
        List<FacetValue> cuisineFacet = Queries.buckets(response, "cuisines").stream()
                .map(b -> new FacetValue(b.key().stringValue(), b.docCount())).toList();
        return new RestaurantSearchResponse(cards, page.page(), page.size(), total, page.totalPages(total),
                new RestaurantFacets(cuisineFacet));
    }

    public RestaurantDetail detail(String restaurantId) {
        RestaurantDocument d = gateway.get(IndexDefinition.RESTAURANTS, restaurantId, RestaurantDocument.class)
                .filter(doc -> Boolean.TRUE.equals(doc.visible()))
                .orElseThrow(() -> new NotFoundException("RESTAURANT_NOT_FOUND",
                        "Restaurant " + restaurantId + " was not found"));
        RestaurantDocument.Address a = d.address();
        Address address = a == null ? null : new Address(a.line1(), a.line2(), a.city(), a.state(), a.postalCode(),
                a.latitude(), a.longitude());
        List<OpeningHoursView> hours = d.openingHoursDisplay() == null ? List.of() : d.openingHoursDisplay().stream()
                .map(h -> new OpeningHoursView(h.dayOfWeek(), h.opensAt(), h.closesAt())).toList();
        List<MenuCategoryView> menu = d.menu() == null ? List.of() : d.menu().stream()
                .filter(category -> category.items() != null && !category.items().isEmpty())
                .map(category -> new MenuCategoryView(category.id(), category.name(), category.description(),
                        category.items().stream()
                                .map(item -> new MenuItemView(item.id(), item.name(), item.description(), item.price(),
                                        item.foodType(), !Boolean.FALSE.equals(item.available()), item.imageUrl()))
                                .toList()))
                .toList();
        return new RestaurantDetail(d.restaurantId(), d.name(), d.description(), cuisines(d),
                OpeningHours.isOpen(d.openingHours(), now()), Boolean.TRUE.equals(d.acceptingOrders()),
                Boolean.TRUE.equals(d.pureVeg()), address, hours, menu, Queries.rating(d.rating()),
                ratingCount(d));
    }

    /**
     * Restaurant name, cuisines and description (synonym-aware), a typo-tolerant name match, and dish names
     * (nested menu items, synonym-aware or typo-tolerant). The best matching dishes come back as inner hits.
     */
    static Query textQuery(String q) {
        return Query.of(root -> root.bool(b -> b
                .should(s -> s.multiMatch(m -> m
                        .query(q)
                        .type(TextQueryType.BestFields)
                        .fields("name^3", "cuisinesText^2", "description")
                        .analyzer(SEARCH_ANALYZER)
                        .operator(Operator.And)))
                .should(s -> s.match(m -> m
                        .field("name")
                        .query(q)
                        .fuzziness(FUZZY)
                        .prefixLength(1)
                        .analyzer(TEXT_ANALYZER)
                        .operator(Operator.And)))
                .should(s -> s.nested(n -> n
                        .path("menuItems")
                        .scoreMode(ChildScoreMode.Max)
                        .query(dish -> dish.bool(db -> db
                                .should(d -> d.match(m -> m
                                        .field("menuItems.name")
                                        .query(q)
                                        .analyzer(SEARCH_ANALYZER)
                                        .operator(Operator.And)))
                                .should(d -> d.match(m -> m
                                        .field("menuItems.name")
                                        .query(q)
                                        .fuzziness(FUZZY)
                                        .prefixLength(1)
                                        .analyzer(TEXT_ANALYZER)
                                        .operator(Operator.And)))
                                .minimumShouldMatch("1")))
                        .innerHits(ih -> ih
                                .name(MATCHING_ITEMS)
                                .size(MAX_MATCHING_ITEMS)
                                .source(src -> src.filter(f -> f.includes("menuItems.itemId", "menuItems.name",
                                        "menuItems.price", "menuItems.foodType"))))))
                .minimumShouldMatch("1")));
    }

    /** Nested filter: one of today's ranges contains the current minute (restaurant time zone). */
    static Query openAt(ZonedDateTime now) {
        String day = now.getDayOfWeek().name();
        double minute = OpeningHours.minuteOfDay(now);
        return Query.of(root -> root.nested(n -> n
                .path("openingHours")
                .query(q -> q.bool(b -> b
                        .filter(Queries.term("openingHours.dayOfWeek", day))
                        .filter(f -> f.range(r -> r.number(num -> num.field("openingHours.opensMinute").lte(minute))))
                        .filter(f -> f.range(r -> r.number(num -> num.field("openingHours.closesMinute").gt(minute))))))));
    }

    private RestaurantCard card(Hit<RestaurantDocument> hit, int distanceSortIndex, ZonedDateTime now,
                                boolean withQuery) {
        RestaurantDocument d = hit.source();
        Double distanceKm = null;
        if (distanceSortIndex >= 0 && hit.sort().size() > distanceSortIndex) {
            FieldValue value = hit.sort().get(distanceSortIndex);
            if (value.isDouble()) {
                distanceKm = Math.round(value.doubleValue() * 10) / 10.0;
            } else if (value.isLong()) {
                distanceKm = (double) value.longValue();
            }
        }
        List<MatchingItem> matchingItems = List.of();
        if (withQuery && hit.innerHits() != null) {
            InnerHitsResult inner = hit.innerHits().get(MATCHING_ITEMS);
            if (inner != null && inner.hits() != null) {
                matchingItems = inner.hits().hits().stream()
                        .filter(h -> h.source() != null)
                        .map(h -> gateway.convert(h.source(), SearchableMenuItem.class))
                        .map(item -> new MatchingItem(item.itemId(), item.name(), item.price(), item.foodType()))
                        .toList();
            }
        }
        return new RestaurantCard(d.restaurantId(), d.name(), d.description(), cuisines(d), d.city(), d.locality(),
                distanceKm, OpeningHours.isOpen(d.openingHours(), now), Boolean.TRUE.equals(d.acceptingOrders()),
                Boolean.TRUE.equals(d.pureVeg()), matchingItems, Queries.rating(d.rating()), ratingCount(d));
    }

    private static int ratingCount(RestaurantDocument d) {
        return d.ratingCount() == null ? 0 : d.ratingCount();
    }

    private static List<String> cuisines(RestaurantDocument d) {
        return d.cuisines() == null ? List.of() : d.cuisines();
    }

    private static GeoLocation geo(Location location) {
        return GeoLocation.of(g -> g.latlon(l -> l.lat(location.lat()).lon(location.lng())));
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock).withZoneSameInstant(properties.zone());
    }
}
