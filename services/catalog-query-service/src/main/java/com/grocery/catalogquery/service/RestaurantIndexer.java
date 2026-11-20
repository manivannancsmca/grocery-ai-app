package com.grocery.catalogquery.service;

import com.grocery.catalogquery.document.RestaurantDocument;
import com.grocery.catalogquery.document.RestaurantDocument.GeoPoint;
import com.grocery.catalogquery.document.RestaurantDocument.MenuCategory;
import com.grocery.catalogquery.document.RestaurantDocument.MenuItem;
import com.grocery.catalogquery.document.RestaurantDocument.OpeningHoursDisplay;
import com.grocery.catalogquery.document.RestaurantDocument.SearchableMenuItem;
import com.grocery.catalogquery.event.EventPayloads;
import com.grocery.catalogquery.event.EventPayloads.RestaurantListingUpdated;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.catalogquery.index.SearchDocumentWriter;
import com.grocery.common.kafka.InvalidEventException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * restaurant.events → RestaurantListingUpdated snapshots → the listing part of the {@code restaurants} documents.
 *
 * <p>Since Phase 12 a restaurant document also has a rating part (review.events, {@code RatingIndexer}), so the
 * listing is merged like the product parts: it replaces only the listing fields, only when its {@code listingVersion}
 * is newer than the stored one, and keeps the rating. (Before Phase 12 the whole document was indexed with
 * listingVersion as Elasticsearch's external version; documents written that way carry {@code listingVersion} in
 * their source, which the merge compares, so existing indexes keep working.)
 */
@Service
public class RestaurantIndexer {

    static final String APPROVED = "APPROVED";
    static final String VEG = "VEG";

    private final SearchDocumentWriter writer;

    public RestaurantIndexer(SearchDocumentWriter writer) {
        this.writer = writer;
    }

    public void onListingUpdated(RestaurantListingUpdated listing) {
        if (listing.restaurantId() == null || listing.restaurantId().isBlank() || listing.listingVersion() == null) {
            throw new InvalidEventException("RestaurantListingUpdated without restaurantId or listingVersion");
        }
        writer.merge(IndexDefinition.RESTAURANTS, listing.restaurantId(), RestaurantDocument.LISTING_VERSION,
                listing.listingVersion(), RestaurantDocument.LISTING_FIELDS, toDocument(listing));
    }

    static RestaurantDocument toDocument(RestaurantListingUpdated r) {
        List<MenuCategory> menu = sorted(r.menu(), EventPayloads.MenuCategory::sortOrder, EventPayloads.MenuCategory::name)
                .stream()
                .map(category -> new MenuCategory(category.categoryId(), category.name(), category.description(),
                        category.sortOrder(),
                        sorted(category.items(), EventPayloads.MenuItem::sortOrder, EventPayloads.MenuItem::name).stream()
                                .map(item -> new MenuItem(item.itemId(), item.name(), item.description(), item.price(),
                                        item.foodType(), item.imageUrl(), !Boolean.FALSE.equals(item.available()),
                                        item.sortOrder()))
                                .toList()))
                .toList();
        List<SearchableMenuItem> menuItems = new ArrayList<>();
        for (MenuCategory category : menu) {
            for (MenuItem item : category.items()) {
                menuItems.add(new SearchableMenuItem(item.id(), item.name(), category.name(), item.price(),
                        item.foodType(), item.available()));
            }
        }
        boolean pureVeg = !menuItems.isEmpty() && menuItems.stream().allMatch(item -> VEG.equals(item.foodType()));

        List<OpeningHours.Day> days = r.openingHours() == null ? List.of() : r.openingHours().stream()
                .filter(Objects::nonNull)
                .map(entry -> new OpeningHours.Day(entry.dayOfWeek(), entry.opensAt(), entry.closesAt()))
                .toList();
        List<String> cuisines = r.cuisines() == null ? List.of()
                : r.cuisines().stream().filter(Objects::nonNull).map(c -> c.trim().toUpperCase(Locale.ROOT)).toList();

        EventPayloads.Address address = r.address();
        GeoPoint location = address != null && address.latitude() != null && address.longitude() != null
                ? new GeoPoint(address.latitude(), address.longitude()) : null;

        return new RestaurantDocument(
                r.restaurantId(),
                r.name(),
                r.description(),
                cuisines,
                cuisines.stream().map(c -> c.replace('_', ' ').toLowerCase(Locale.ROOT)).collect(Collectors.joining(" ")),
                address == null ? null : address.city(),
                address == null || address.line2() == null || address.line2().isBlank() ? null : address.line2(),
                location,
                address == null ? null : new RestaurantDocument.Address(address.line1(), address.line2(),
                        address.city(), address.state(), address.postalCode(), address.country(),
                        address.latitude(), address.longitude()),
                OpeningHours.toRanges(days),
                days.stream().map(d -> new OpeningHoursDisplay(d.dayOfWeek(), d.opensAt(), d.closesAt())).toList(),
                !Boolean.FALSE.equals(r.acceptingOrders()),
                pureVeg,
                r.status(),
                APPROVED.equals(r.status()),
                menuItems,
                menu,
                r.listingVersion(),
                r.updatedAt(),
                null, null, null);                  // rating part: written by RatingSummaryUpdated
    }

    /** Sorted by sortOrder (missing last), then name; null entries dropped. */
    private static <T> List<T> sorted(List<T> values, Function<T, Integer> sortOrder, Function<T, String> name) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(sortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }
}
