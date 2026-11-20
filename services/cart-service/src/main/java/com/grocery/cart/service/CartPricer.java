package com.grocery.cart.service;

import com.grocery.cart.client.DownstreamDtos.AvailabilityItem;
import com.grocery.cart.client.DownstreamDtos.MenuQuote;
import com.grocery.cart.client.DownstreamDtos.MenuQuoteItem;
import com.grocery.cart.client.DownstreamDtos.ProductCard;
import com.grocery.cart.client.DownstreamDtos.QuoteItem;
import com.grocery.cart.client.DownstreamDtos.VendorStore;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.dto.CartDtos;
import com.grocery.cart.dto.CartDtos.CartLine;
import com.grocery.cart.dto.CartDtos.CartView;
import com.grocery.cart.dto.CartDtos.Issue;
import com.grocery.cart.dto.CartDtos.StoreView;
import com.grocery.cart.model.StoreType;
import com.grocery.cart.model.StoredCart;
import com.grocery.cart.model.StoredLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prices a stored cart with live data and finds its issues (contract §2). Shared by {@code GET /api/cart}, the
 * responses of every write and the checkout preview.
 *
 * <h2>One issue per line</h2>
 * A line carries its most important issue, in this order: ITEM_UNAVAILABLE (the owner says it can't be ordered),
 * PRICING_UNAVAILABLE (no live price: {@code unitPrice} null), AVAILABILITY_UNKNOWN (priced, but catalog or
 * inventory could not be reached), OUT_OF_STOCK, INSUFFICIENT_STOCK, then the non-blocking PRICE_CHANGED. Lines
 * that cannot be ordered (ITEM_UNAVAILABLE) or priced show {@code unitPrice: null} and don't count in the
 * subtotal. Store problems (STORE_UNAVAILABLE, STORE_CLOSED, NOT_ACCEPTING_ORDERS, or AVAILABILITY_UNKNOWN when
 * the store could not be checked) are issues of the whole cart ({@code itemId} null).
 */
@Component
public class CartPricer {

    public PricedCart price(StoredCart cart, LiveData live) {
        if (cart.isEmpty()) {
            CartView empty = new CartView(null, List.of(), 0, Money.ZERO, Money.ZERO, Money.ZERO,
                    CartDtos.CURRENCY, List.of(), cart.updatedAt(), null);
            return new PricedCart(empty, null, false, true);
        }
        return switch (live) {
            case LiveData.Grocery grocery when cart.store().type() == StoreType.GROCERY -> priceGrocery(cart, grocery);
            case LiveData.Restaurant restaurant when cart.store().type() == StoreType.RESTAURANT ->
                    priceRestaurant(cart, restaurant);
            default -> throw new IllegalArgumentException("Live data does not match the cart's store");
        };
    }

    // ---------------------------------------------------------------- grocery

    private PricedCart priceGrocery(StoredCart cart, LiveData.Grocery live) {
        boolean catalogOk = live.catalog().isOk();
        boolean quotesOk = live.quotes().isOk();
        boolean stockOk = live.stock().isOk();
        Map<UUID, ProductCard> cards = catalogOk ? live.catalog().valueOrNull().byId() : Map.of();
        Map<UUID, QuoteItem> quotes = quotesOk ? live.quotes().valueOrNull().byId() : Map.of();
        Map<UUID, AvailabilityItem> stock = stockOk ? live.stock().valueOrNull().byId() : Map.of();
        Outcome<VendorStore> storeOutcome = live.store();
        VendorStore vendor = storeOutcome == null ? null : storeOutcome.valueOrNull();

        String storeName = storeName(cart, vendor, cards);
        List<Issue> issues = new ArrayList<>();
        if (storeOutcome != null) {
            if (storeOutcome instanceof Outcome.NotFound<VendorStore> || (vendor != null && !vendor.isActive())) {
                issues.add(IssueCode.STORE_UNAVAILABLE.issue(null, storeName + " is not taking orders at the moment"));
            } else if (storeOutcome.isFailed()) {
                issues.add(IssueCode.AVAILABILITY_UNKNOWN.issue(null,
                        "Whether " + storeName + " is taking orders could not be checked. Please try again shortly."));
            }
        }

        Totals totals = new Totals();
        List<CartLine> lines = new ArrayList<>();
        for (StoredLine line : cart.lines()) {
            ProductCard card = cards.get(line.itemId());
            QuoteItem quote = quotes.get(line.itemId());
            AvailabilityItem item = stock.get(line.itemId());
            String name = card != null && card.name() != null ? card.name() : line.name();
            String unitLabel = card != null ? card.unitLabel() : line.unitLabel();
            String imageUrl = card != null ? card.primaryImageUrl() : line.imageUrl();
            String foodType = card != null ? card.foodType() : line.foodType();
            Integer available = null;                       // unknown while inventory can't be reached
            if (item != null) {
                available = item.availableQuantity();
            } else if (stockOk) {
                available = 0;                              // unknown to inventory: nothing in stock
            }

            BigDecimal unitPrice = null;
            BigDecimal mrp = null;
            IssueCode issue;
            if (catalogOk && card == null) {
                issue = IssueCode.ITEM_UNAVAILABLE;
            } else if (!quotesOk) {
                issue = IssueCode.PRICING_UNAVAILABLE;
            } else if (quote == null || quote.sellingPrice() == null) {
                issue = IssueCode.ITEM_UNAVAILABLE;
            } else {
                unitPrice = Money.of(quote.sellingPrice());
                mrp = quote.mrp() == null ? unitPrice : Money.of(quote.mrp());
                if (!catalogOk || !stockOk) {
                    issue = IssueCode.AVAILABILITY_UNKNOWN;
                } else if (item == null || !item.orderable()) {
                    issue = IssueCode.OUT_OF_STOCK;
                } else if (line.quantity() > item.availableQuantity()) {
                    issue = IssueCode.INSUFFICIENT_STOCK;
                } else {
                    issue = priceChanged(line, unitPrice);
                }
            }
            lines.add(line(line, name, unitLabel, imageUrl, foodType, unitPrice, mrp, available, issue, issues,
                    totals));
        }
        BigDecimal savings = totals.mrpTotal.subtract(totals.subtotal).max(Money.ZERO);
        Coordinates location = vendor == null ? null : Coordinates.of(vendor.latitude(), vendor.longitude());
        StoreView store = new StoreView(cart.store().id(), StoreType.GROCERY, storeName,
                vendor == null ? null : vendor.city(), latitude(location), longitude(location));
        CartView view = new CartView(store, lines, totals.itemCount, totals.subtotal, totals.mrpTotal,
                Money.of(savings), CartDtos.CURRENCY, issues, cart.updatedAt(), null);
        return new PricedCart(view, location, storeOutcome != null && storeOutcome.isOk(), quotesOk);
    }

    private static String storeName(StoredCart cart, VendorStore vendor, Map<UUID, ProductCard> cards) {
        if (vendor != null && vendor.name() != null) {
            return vendor.name();
        }
        return cards.values().stream()
                .map(ProductCard::vendor)
                .filter(ref -> ref != null && ref.name() != null && cart.store().id().equals(ref.id()))
                .map(ref -> ref.name())
                .findFirst()
                .orElse(cart.store().name());
    }

    // ---------------------------------------------------------------- restaurant

    private PricedCart priceRestaurant(StoredCart cart, LiveData.Restaurant live) {
        Outcome<MenuQuote> outcome = live.menu();
        MenuQuote menu = outcome.valueOrNull();
        String storeName = menu != null && menu.name() != null ? menu.name() : cart.store().name();
        List<Issue> issues = new ArrayList<>();
        if (outcome instanceof Outcome.NotFound<MenuQuote> || (menu != null && !menu.approved())) {
            issues.add(IssueCode.STORE_UNAVAILABLE.issue(null, storeName + " is not taking orders at the moment"));
        } else if (menu != null) {
            if (!menu.isOpenNow()) {
                issues.add(IssueCode.STORE_CLOSED.issue(null, storeName + " is closed right now"));
            }
            if (!menu.isAcceptingOrders()) {
                issues.add(IssueCode.NOT_ACCEPTING_ORDERS.issue(null,
                        storeName + " is not accepting orders right now"));
            }
        }

        Map<UUID, MenuQuoteItem> items = menu == null ? Map.of() : menu.byId();
        Totals totals = new Totals();
        List<CartLine> lines = new ArrayList<>();
        for (StoredLine line : cart.lines()) {
            MenuQuoteItem item = items.get(line.itemId());
            String name = item != null && item.name() != null ? item.name() : line.name();
            String imageUrl = item != null ? item.imageUrl() : line.imageUrl();
            String foodType = item != null ? item.foodType() : line.foodType();
            BigDecimal unitPrice = null;
            IssueCode issue;
            if (outcome.isFailed()) {
                issue = IssueCode.PRICING_UNAVAILABLE;
            } else if (item == null || !item.orderable()) {
                issue = IssueCode.ITEM_UNAVAILABLE;
            } else {
                unitPrice = Money.of(item.price());
                issue = priceChanged(line, unitPrice);
            }
            lines.add(line(line, name, line.unitLabel(), imageUrl, foodType, unitPrice, null, null, issue, issues,
                    totals));
        }
        // Restaurant prices have no MRP: mrpTotal = subtotal, savings 0.
        Coordinates location = menu == null ? null : Coordinates.of(menu.latitude(), menu.longitude());
        StoreView store = new StoreView(cart.store().id(), StoreType.RESTAURANT, storeName,
                menu == null ? null : menu.city(), latitude(location), longitude(location));
        CartView view = new CartView(store, lines, totals.itemCount, totals.subtotal, totals.subtotal, Money.ZERO,
                CartDtos.CURRENCY, issues, cart.updatedAt(), null);
        return new PricedCart(view, location, outcome.isOk(), !outcome.isFailed());
    }

    // ---------------------------------------------------------------- shared

    /** Both coordinates or neither: a half-set location is reported as unknown. */
    private static Double latitude(Coordinates location) {
        return location == null ? null : location.latitude();
    }

    private static Double longitude(Coordinates location) {
        return location == null ? null : location.longitude();
    }

    private static IssueCode priceChanged(StoredLine line, BigDecimal unitPrice) {
        return line.priceWhenAdded() != null && unitPrice.compareTo(line.priceWhenAdded()) != 0
                ? IssueCode.PRICE_CHANGED : null;
    }

    private static CartLine line(StoredLine line, String name, String unitLabel, String imageUrl, String foodType,
                                 BigDecimal unitPrice, BigDecimal mrp, Integer available, IssueCode issue,
                                 List<Issue> issues, Totals totals) {
        BigDecimal lineTotal = unitPrice == null ? null : Money.times(unitPrice, line.quantity());
        totals.itemCount += line.quantity();
        if (lineTotal != null) {
            totals.subtotal = totals.subtotal.add(lineTotal);
            totals.mrpTotal = totals.mrpTotal.add(Money.times(mrp == null ? unitPrice : mrp, line.quantity()));
        }
        if (issue != null) {
            issues.add(issue.issue(line.itemId(), IssueCode.lineMessage(issue, name, line, unitPrice, available)));
        }
        return new CartLine(line.itemId(), line.type(), name, unitLabel, imageUrl, foodType, line.quantity(),
                unitPrice, mrp, lineTotal, Money.of(line.priceWhenAdded()), available,
                issue == null ? null : issue.name());
    }

    private static final class Totals {
        int itemCount;
        BigDecimal subtotal = Money.ZERO;
        BigDecimal mrpTotal = Money.ZERO;
    }

    /** A point on the map. */
    public record Coordinates(double latitude, double longitude) {

        static Coordinates of(Double latitude, Double longitude) {
            return latitude == null || longitude == null ? null : new Coordinates(latitude, longitude);
        }
    }

    /**
     * A priced cart plus what the checkout preview needs.
     *
     * @param storeLocation    the store's coordinates; null when unknown or not set
     * @param storeChecked     the store's owner answered with its data (vendor store / menu quote)
     * @param pricingAvailable the prices could be fetched (no PRICING_UNAVAILABLE)
     */
    public record PricedCart(CartView view, Coordinates storeLocation, boolean storeChecked,
                             boolean pricingAvailable) {
    }
}
