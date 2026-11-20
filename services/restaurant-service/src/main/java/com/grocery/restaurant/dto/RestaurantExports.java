package com.grocery.restaurant.dto;

import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.export.ExportTable;
import com.grocery.restaurant.dto.RestaurantDtos.AddressDto;
import com.grocery.restaurant.dto.RestaurantDtos.RestaurantView;
import com.grocery.restaurant.entity.Restaurant;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The CSV / Excel / PDF export of the admin restaurant list. */
public final class RestaurantExports {

    private RestaurantExports() {
    }

    /** The review queue (PENDING_APPROVAL), oldest submission first, like the list (JPQL paths, alias {@code r}). */
    public static final Order<Restaurant> REVIEW_QUEUE = Order.of(
            Key.asc("r.submittedAt", Restaurant::getSubmittedAt), Key.asc("r.id", Restaurant::getId));

    /** Every other list: most recently changed first, like the list. */
    public static final Order<Restaurant> RECENTLY_CHANGED = Order.of(
            Key.desc("r.updatedAt", Restaurant::getUpdatedAt), Key.desc("r.id", Restaurant::getId));

    /**
     * {@code GET /api/admin/restaurants/export}. The owner's e-mail, phone and street address are personal
     * ({@link ExportTable#visibleTo}); the area (city, state, PIN code) is always there. FSSAI licence and GSTIN are
     * business registrations an admin reviews; the profile holds no bank details.
     */
    public static final ExportTable<RestaurantView> RESTAURANTS = ExportTable.<RestaurantView>named(
                    "restaurants", "Restaurants")
            .text("Name", RestaurantView::name)
            .text("Restaurant ID", RestaurantView::id)
            .label("Status", RestaurantView::status)
            .text("Cuisines", view -> cuisines(view.cuisines()))
            .yesNo("Accepting orders", RestaurantView::acceptingOrders)
            .text("FSSAI licence", RestaurantView::fssaiLicense)
            .text("GSTIN", RestaurantView::gstin)
            .text("Contact email", RestaurantView::contactEmail).personal()
            .text("Contact phone", RestaurantView::contactPhone).personal()
            .text("Street address", view -> street(view.address())).personal()
            .text("City", view -> view.address() == null ? null : view.address().city())
            .text("State", view -> view.address() == null ? null : view.address().state())
            .text("PIN code", view -> view.address() == null ? null : view.address().postalCode())
            .text("Status reason", RestaurantView::statusReason)
            .dateTime("Submitted", RestaurantView::submittedAt)
            .dateTime("Approved", RestaurantView::approvedAt)
            .dateTime("Registered", RestaurantView::createdAt)
            .dateTime("Last change", RestaurantView::updatedAt)
            .build();

    /** Cuisine tokens as the admin page shows them: {@code NORTH_INDIAN} → "North Indian". */
    private static String cuisines(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        return tokens.stream().map(RestaurantExports::titleCase).collect(Collectors.joining(", "));
    }

    private static String titleCase(String token) {
        return Arrays.stream(token.toLowerCase(Locale.ROOT).split("_"))
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    private static String street(AddressDto address) {
        if (address == null) {
            return null;
        }
        String joined = Stream.of(address.line1(), address.line2()).filter(Objects::nonNull).map(String::trim)
                .filter(part -> !part.isEmpty()).collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }
}
