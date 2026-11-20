package com.grocery.cart.service;

import com.grocery.cart.client.CallContext;
import com.grocery.cart.client.CustomerClient;
import com.grocery.cart.client.Dependency;
import com.grocery.cart.client.DownstreamDtos.Availability;
import com.grocery.cart.client.DownstreamDtos.AvailabilityItem;
import com.grocery.cart.client.DownstreamDtos.CouponEvaluation;
import com.grocery.cart.client.DownstreamDtos.CustomerAddress;
import com.grocery.cart.client.DownstreamDtos.MenuQuote;
import com.grocery.cart.client.DownstreamDtos.MenuQuoteItem;
import com.grocery.cart.client.DownstreamDtos.PriceQuote;
import com.grocery.cart.client.DownstreamDtos.ProductCard;
import com.grocery.cart.client.DownstreamDtos.ProductLookup;
import com.grocery.cart.client.DownstreamDtos.QuoteItem;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.config.CartProperties;
import com.grocery.cart.dto.CartDtos.AddItemRequest;
import com.grocery.cart.dto.CartDtos.CartView;
import com.grocery.cart.dto.CartDtos.CheckoutPreview;
import com.grocery.cart.model.ItemType;
import com.grocery.cart.model.Store;
import com.grocery.cart.model.StoreType;
import com.grocery.cart.model.StoredCart;
import com.grocery.cart.model.StoredCoupon;
import com.grocery.cart.model.StoredLine;
import com.grocery.cart.model.StoredLine.Snapshot;
import com.grocery.cart.service.CartPricer.PricedCart;
import com.grocery.cart.service.CouponChecker.AppliedCoupon;
import com.grocery.cart.store.CartStore;
import com.grocery.cart.store.CartStore.VersionedCart;
import com.grocery.cart.web.CartErrors;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * The cart use cases (contract §2, §3). Each customer's cart is one Redis hash; customerId = the JWT subject.
 *
 * <h2>Writes</h2>
 * Adding or changing a quantity first checks the item live (it must be orderable right now, otherwise nothing
 * changes; a dependency that can't be reached → 503 DEPENDENCY_UNAVAILABLE), then {@link #write} applies the change
 * to a fresh read of the cart and stores it with a compare-and-set on the cart's version — retried
 * {@code grocery.cart.write-retries} times with a short random pause, then 409 CONCURRENT_MODIFICATION. Store
 * conflict, line and cart limits and the stock check are evaluated against that fresh read, so two tabs can never
 * lose each other's changes or exceed a limit together.
 *
 * <h2>Reads</h2>
 * Every response re-prices the cart live ({@link CartPricer}); the data fetched to check an added item is reused,
 * so each dependency is called once per request (one batch call for all lines). Phase 12: a cart with a coupon also
 * asks pricing-service's {@code evaluate} once, after pricing ({@link CouponChecker}).
 */
@Service
public class CartService {

    private final CartStore store;
    private final LiveDataFetcher fetcher;
    private final CartPricer pricer;
    private final CheckoutPreviewBuilder previews;
    private final CouponChecker coupons;
    private final CustomerClient customers;
    private final DeliveryFeeRules deliveryFees;
    private final CartProperties properties;
    private final Clock clock;

    public CartService(CartStore store, LiveDataFetcher fetcher, CartPricer pricer, CheckoutPreviewBuilder previews,
                       CouponChecker coupons, CustomerClient customers, DeliveryFeeRules deliveryFees,
                       CartProperties properties, Clock clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.pricer = pricer;
        this.previews = previews;
        this.coupons = coupons;
        this.customers = customers;
        this.deliveryFees = deliveryFees;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    public CartView get(UUID customerId) {
        StoredCart cart = store.load(customerId).cart();
        CallContext context = CallContext.current();
        return respond(context, customerId, cart, fetcher.forCart(context, cart));
    }

    /**
     * The checkout preview for {@code addressId}, or the customer's default address when it is null. The cart's
     * live data, the address and the delivery fees in force (cached, see {@link DeliveryFeeRules}) are fetched in
     * parallel.
     */
    public CheckoutPreview preview(UUID customerId, UUID addressId) {
        CallContext context = CallContext.current();
        StoredCart cart = store.load(customerId).cart();
        LiveData live;
        CustomerAddress address;
        DeliveryFeeRules.Rules rules;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<LiveData> liveData = executor.submit(() -> fetcher.forCart(context, cart));
            Future<CustomerAddress> deliveryAddress = executor.submit(() -> address(context, addressId));
            Future<DeliveryFeeRules.Rules> feeRules = executor.submit(() -> deliveryFees.current(context));
            live = Parallel.join(liveData);
            address = Parallel.join(deliveryAddress);
            rules = Parallel.join(feeRules);
        }
        PricedCart priced = pricer.price(cart, live);
        return previews.build(priced, address, rules, clock.instant(),
                deliveryFee -> coupons.check(context, customerId, cart, priced, deliveryFee));
    }

    /** The given address (404 ADDRESS_NOT_FOUND when not the caller's) or the default one (null when none). */
    private CustomerAddress address(CallContext context, UUID addressId) {
        if (addressId != null) {
            Outcome<CustomerAddress> address = customers.address(context, addressId);
            if (address instanceof Outcome.NotFound<CustomerAddress>) {
                throw CartErrors.addressNotFound();
            }
            return address.requireAvailable();
        }
        List<CustomerAddress> addresses = customers.addresses(context).requireAvailable();
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        return addresses.stream()
                .filter(a -> Boolean.TRUE.equals(a.isDefault()))
                .findFirst()
                .orElse(addresses.getFirst());
    }

    // ---------------------------------------------------------------- writes

    public CartView add(UUID customerId, AddItemRequest request) {
        validate(request);
        int quantity = request.quantity();
        int maxPerLine = properties.getMaxQuantityPerLine();
        if (quantity > maxPerLine) {
            throw CartErrors.quantityLimit(maxPerLine);
        }
        CallContext context = CallContext.current();
        StoredCart current = store.load(customerId).cart();
        Candidate candidate;
        LiveData live;
        if (request.type() == ItemType.PRODUCT) {
            Set<UUID> ids = new LinkedHashSet<>();
            if (!current.isEmpty() && current.store().type() == StoreType.GROCERY) {
                ids.addAll(current.itemIds());           // one batch for the check and the response
            }
            ids.add(request.productId());
            LiveData.Grocery grocery = fetcher.grocery(context, ids, null);
            candidate = productCandidate(grocery, request.productId(), null);
            live = grocery;
        } else {
            Set<UUID> ids = new LinkedHashSet<>();
            if (current.belongsTo(StoreType.RESTAURANT, request.restaurantId())) {
                ids.addAll(current.itemIds());
            }
            ids.add(request.menuItemId());
            LiveData.Restaurant restaurant = fetcher.restaurant(context, request.restaurantId(), ids);
            candidate = menuCandidate(restaurant, request.menuItemId());
            live = restaurant;
        }
        Instant now = clock.instant();
        StoredCart updated = write(customerId, cart -> applyAdd(cart, candidate, quantity, request.replace(), now));
        return respond(context, customerId, updated, fetcher.completeFor(context, updated, live));
    }

    /** PUT: sets a line's quantity (0 removes it); the item is checked live like an add. */
    public CartView updateQuantity(UUID customerId, UUID itemId, int quantity) {
        if (quantity == 0) {
            Instant now = clock.instant();
            StoredCart updated = write(customerId, cart -> {
                if (cart.line(itemId) == null) {
                    throw CartErrors.cartItemNotFound();
                }
                return cart.without(itemId, now);
            });
            return view(customerId, updated);
        }
        int maxPerLine = properties.getMaxQuantityPerLine();
        if (quantity > maxPerLine) {
            throw CartErrors.quantityLimit(maxPerLine);
        }
        CallContext context = CallContext.current();
        StoredCart current = store.load(customerId).cart();
        StoredLine line = current.line(itemId);
        if (line == null) {
            throw CartErrors.cartItemNotFound();
        }
        LiveData live = fetcher.forCart(context, current);
        Candidate candidate = switch (live) {
            case LiveData.Grocery grocery -> productCandidate(grocery, itemId, current.store());
            case LiveData.Restaurant restaurant -> menuCandidate(restaurant, itemId);
            case LiveData.None none -> throw CartErrors.cartItemNotFound();
        };
        Instant now = clock.instant();
        StoredCart updated = write(customerId, cart -> {
            StoredLine existing = cart.line(itemId);
            if (existing == null || !candidate.store().sameAs(cart.store())) {
                throw CartErrors.cartItemNotFound();         // removed meanwhile (another tab)
            }
            if (candidate.available() != null && quantity > candidate.available()) {
                throw CartErrors.insufficientStock(candidate.display().name(), candidate.available());
            }
            return cart.withLine(candidate.store(),
                    existing.withQuantity(quantity, candidate.price(), candidate.display()), now);
        });
        return respond(context, customerId, updated, fetcher.completeFor(context, updated, live));
    }

    /** DELETE of one line; idempotent (a line that is not there is simply not there). */
    public CartView remove(UUID customerId, UUID itemId) {
        Instant now = clock.instant();
        return view(customerId, write(customerId, cart -> cart.without(itemId, now)));
    }

    /**
     * Phase 12 §2, {@code PUT /api/cart/coupon}: applies (or replaces) the cart's one coupon. The cart is priced live
     * and the coupon evaluated for its store and subtotal (no delivery fee yet); one that cannot be used → 422
     * COUPON_NOT_APPLICABLE with {@code reason} (and {@code minOrderSubtotal}), and nothing is stored. An empty cart →
     * 422 with reason EMPTY_CART. Prices or pricing-service unavailable → 503 DEPENDENCY_UNAVAILABLE (like adding).
     */
    public CartView applyCoupon(UUID customerId, String rawCode) {
        String code = CouponChecker.normalize(rawCode);
        CallContext context = CallContext.current();
        StoredCart current = store.load(customerId).cart();
        if (current.isEmpty()) {
            throw CartErrors.couponNotApplicable(CouponChecker.EMPTY_CART, code, null,
                    CouponChecker.message(CouponChecker.EMPTY_CART, code, null, null, null));
        }
        LiveData live = fetcher.forCart(context, current);
        PricedCart priced = pricer.price(current, live);
        if (!priced.pricingAvailable()) {
            throw CartErrors.dependencyUnavailable(
                    current.store().type() == StoreType.GROCERY ? Dependency.PRICING : Dependency.RESTAURANT);
        }
        BigDecimal subtotal = priced.view().subtotal();
        String storeName = priced.view().store().name();
        CouponEvaluation result = coupons.evaluate(context, customerId, code, current.store(), subtotal, Money.ZERO)
                .requireAvailable();
        if (result == null || !result.isValid()) {
            String reason = result == null || result.reason() == null ? "COUPON_NOT_APPLICABLE" : result.reason();
            BigDecimal minimum = result == null ? null : Money.of(result.minOrderSubtotal());
            throw CartErrors.couponNotApplicable(reason, code, minimum,
                    CouponChecker.message(reason, code, minimum, subtotal, storeName));
        }
        String storedCode = result.code() == null ? code : result.code();
        Instant now = clock.instant();
        StoredCoupon coupon = new StoredCoupon(storedCode, result.scope(), result.storeId(), now);
        StoredCart updated = write(customerId, cart -> {
            if (cart.isEmpty() || !cart.store().sameAs(current.store())) {
                throw CartErrors.concurrentModification();      // emptied or moved to another store meanwhile
            }
            return cart.withCoupon(coupon, now);
        });
        PricedCart repriced = pricer.price(updated, fetcher.completeFor(context, updated, live));
        if (repriced.view().subtotal().compareTo(subtotal) != 0) {
            return decorate(context, customerId, updated, repriced);   // lines changed meanwhile: check again
        }
        AppliedCoupon applied = coupons.from(storedCode, result, subtotal, Money.ZERO, storeName);
        return repriced.view().withCoupon(applied.view(), applied.issue());
    }

    /** Phase 12 §2, {@code DELETE /api/cart/coupon}: removes the coupon (idempotent). */
    public CartView removeCoupon(UUID customerId) {
        Instant now = clock.instant();
        return view(customerId, write(customerId, cart -> cart.withoutCoupon(now)));
    }

    public void clear(UUID customerId) {
        Instant now = clock.instant();
        write(customerId, cart -> cart.cleared(now));
    }

    /**
     * {@code OrderCreated} (Phase 6 contract §6 "Cart"): empties the customer's cart <b>only if it has not changed
     * since the order was placed from it</b> — its {@code updatedAt} still equals {@code cartUpdatedAt} (the
     * checkout preview's {@code updatedAt}, copied into the event by order-service), compared at the cart's own
     * precision (milliseconds, see {@code CartConfig}). A cart edited after the order was placed is kept, and a
     * missing or already empty cart is left alone (nothing is written, no key is created).
     *
     * <p>The check and the clear run through {@link #write}: the clear is stored with a compare-and-set on the
     * version the check read, so an edit that lands in between makes it fail and the check is repeated on the edited
     * cart — whose {@code updatedAt} no longer matches — and the edit is never lost. Idempotent by nature: a cleared
     * cart is empty and carries the clear time, so a redelivered event changes nothing.
     *
     * @return true when this call emptied the cart
     */
    public boolean clearIfUnchangedSince(UUID customerId, Instant cartUpdatedAt) {
        Instant placedFrom = cartUpdatedAt.truncatedTo(ChronoUnit.MILLIS);
        Instant now = clock.instant();
        AtomicBoolean cleared = new AtomicBoolean();
        write(customerId, cart -> {
            boolean unchanged = !cart.isEmpty() && cart.updatedAt() != null
                    && cart.updatedAt().truncatedTo(ChronoUnit.MILLIS).equals(placedFrom);
            cleared.set(unchanged);                 // the last attempt is the one that counts
            return unchanged ? cart.cleared(now) : cart;
        });
        return cleared.get();
    }

    private CartView view(UUID customerId, StoredCart cart) {
        CallContext context = CallContext.current();
        return respond(context, customerId, cart, fetcher.forCart(context, cart));
    }

    /** The cart priced with {@code live}, with its coupon checked (Phase 12; no delivery fee outside the preview). */
    private CartView respond(CallContext context, UUID customerId, StoredCart cart, LiveData live) {
        return decorate(context, customerId, cart, pricer.price(cart, live));
    }

    private CartView decorate(CallContext context, UUID customerId, StoredCart cart, PricedCart priced) {
        AppliedCoupon applied = coupons.check(context, customerId, cart, priced, Money.ZERO);
        return applied == null ? priced.view() : priced.view().withCoupon(applied.view(), applied.issue());
    }

    /**
     * Read → change → compare-and-set, retried on a concurrent write. {@code change} may throw (nothing is
     * written) and returns the same instance when there is nothing to change (nothing is written either).
     */
    StoredCart write(UUID customerId, UnaryOperator<StoredCart> change) {
        int attempts = 1 + Math.max(0, properties.getWriteRetries());
        for (int attempt = 1; ; attempt++) {
            VersionedCart current = store.load(customerId);
            StoredCart updated = change.apply(current.cart());
            if (updated == current.cart() || store.compareAndSet(customerId, current.version(), updated)) {
                return updated;
            }
            if (attempt >= attempts) {
                throw CartErrors.concurrentModification();
            }
            pause(attempt);
        }
    }

    /** Exponential backoff with full jitter (up to 25, 50, 100 ms), so colliding writers spread out. */
    private static void pause(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, (25L << (attempt - 1)) + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CartErrors.concurrentModification();
        }
    }

    // ---------------------------------------------------------------- the item being added / changed

    /** An item that passed the live checks, with what a line stores about it. */
    private record Candidate(Store store, ItemType type, UUID itemId, BigDecimal price, Snapshot display,
                             Integer available) {
    }

    /**
     * Checks a product (visible, priced, in stock) with live data. {@code cartStore} is the cart's store when the
     * product is already in the cart (its vendor never changes), else null (store = the product's vendor).
     */
    private static Candidate productCandidate(LiveData.Grocery live, UUID productId, Store cartStore) {
        ProductLookup lookup = live.catalog().requireAvailable();
        PriceQuote quotes = live.quotes().requireAvailable();
        Availability stock = live.stock().requireAvailable();
        ProductCard card = lookup.byId().get(productId);
        if (card == null || card.vendor() == null || card.vendor().id() == null) {
            throw CartErrors.itemUnavailable(card == null ? null : card.name());
        }
        QuoteItem quote = quotes.byId().get(productId);
        if (quote == null || quote.sellingPrice() == null) {
            throw CartErrors.itemUnavailable(card.name());
        }
        AvailabilityItem item = stock.byId().get(productId);
        if (item == null || !item.orderable()) {
            throw CartErrors.outOfStock(card.name());
        }
        String storeName = card.vendor().name() != null ? card.vendor().name()
                : cartStore != null ? cartStore.name() : null;
        Store store = new Store(cartStore != null ? cartStore.id() : card.vendor().id(), StoreType.GROCERY, storeName);
        return new Candidate(store, ItemType.PRODUCT, productId, Money.of(quote.sellingPrice()),
                new Snapshot(card.name(), card.unitLabel(), card.primaryImageUrl(), card.foodType()),
                item.availableQuantity());
    }

    /** Checks a menu item (restaurant approved, item on the menu and available, open now, accepting orders). */
    private static Candidate menuCandidate(LiveData.Restaurant live, UUID menuItemId) {
        MenuQuote menu = live.menu().requireAvailable();
        if (menu == null || !menu.approved()) {
            throw CartErrors.itemUnavailable(null);
        }
        MenuQuoteItem item = menu.byId().get(menuItemId);
        if (item == null || !item.orderable()) {
            throw CartErrors.itemUnavailable(item == null ? null : item.name());
        }
        if (!menu.isOpenNow()) {
            throw CartErrors.storeClosed(menu.name());
        }
        if (!menu.isAcceptingOrders()) {
            throw CartErrors.notAcceptingOrders(menu.name());
        }
        return new Candidate(new Store(live.restaurantId(), StoreType.RESTAURANT, menu.name()), ItemType.MENU_ITEM,
                menuItemId, Money.of(item.price()), new Snapshot(item.name(), null, item.imageUrl(), item.foodType()),
                null);
    }

    /**
     * Adds {@code quantity} of the candidate to a fresh read of the cart: store conflict (unless
     * {@code replaceCart}), line limit, cart limit, then stock for the merged quantity.
     */
    private StoredCart applyAdd(StoredCart cart, Candidate candidate, int quantity, boolean replaceCart, Instant now) {
        StoredCart base = cart;
        if (!cart.isEmpty() && !cart.store().sameAs(candidate.store())) {
            if (!replaceCart) {
                throw CartErrors.storeConflict(cart.store());
            }
            base = cart.withoutLines();     // a PLATFORM coupon stays; withLine drops a STORE coupon of the old store
        }
        StoredLine existing = base.line(candidate.itemId());
        int newQuantity = (existing == null ? 0 : existing.quantity()) + quantity;
        if (newQuantity > properties.getMaxQuantityPerLine()) {
            throw CartErrors.quantityLimit(properties.getMaxQuantityPerLine());
        }
        if (existing == null && base.lines().size() >= properties.getMaxLines()) {
            throw CartErrors.cartLimitReached(properties.getMaxLines());
        }
        if (candidate.available() != null && newQuantity > candidate.available()) {
            throw CartErrors.insufficientStock(candidate.display().name(), candidate.available());
        }
        StoredLine line = existing == null
                ? new StoredLine(candidate.itemId(), candidate.type(), newQuantity, candidate.price(),
                candidate.display().name(), candidate.display().unitLabel(), candidate.display().imageUrl(),
                candidate.display().foodType(), now)
                : existing.withQuantity(newQuantity, candidate.price(), candidate.display());
        return base.withLine(candidate.store(), line, now);
    }

    /** Exactly one kind of item: PRODUCT → productId; MENU_ITEM → restaurantId + menuItemId. */
    private static void validate(AddItemRequest request) {
        if (request.type() == ItemType.PRODUCT) {
            if (request.productId() == null) {
                throw CartErrors.invalidField("productId", "is required for type PRODUCT");
            }
            if (request.restaurantId() != null || request.menuItemId() != null) {
                throw CartErrors.invalidField("type", "a PRODUCT has no restaurantId or menuItemId");
            }
        } else {
            if (request.restaurantId() == null) {
                throw CartErrors.invalidField("restaurantId", "is required for type MENU_ITEM");
            }
            if (request.menuItemId() == null) {
                throw CartErrors.invalidField("menuItemId", "is required for type MENU_ITEM");
            }
            if (request.productId() != null) {
                throw CartErrors.invalidField("type", "a MENU_ITEM has no productId");
            }
        }
    }
}
