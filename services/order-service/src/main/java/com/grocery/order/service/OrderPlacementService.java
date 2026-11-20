package com.grocery.order.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.IdempotencyKeys;
import com.grocery.common.web.ApiException;
import com.grocery.order.client.CallContext;
import com.grocery.order.client.CartClient;
import com.grocery.order.client.CartPreview;
import com.grocery.order.client.Dependency;
import com.grocery.order.client.Outcome;
import com.grocery.order.client.PricingClient;
import com.grocery.order.config.OrderProperties;
import com.grocery.order.dto.OrderDtos.OrderView;
import com.grocery.order.dto.OrderDtos.PlaceOrderRequest;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.DeliveryAddress;
import com.grocery.order.entity.ItemType;
import com.grocery.order.entity.OrderItem;
import com.grocery.order.entity.OrderType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code POST /api/orders} (contract §2 "Placing an order"), in this order:
 * <ol>
 *   <li>the {@code Idempotency-Key} header (400 when missing / malformed), then the body;</li>
 *   <li>a fast, non-locking look-up of the key: a retry of an earlier success is answered with that order at once
 *       (nothing is created or re-checked); the same key with a different body → 422 {@code IDEMPOTENCY_KEY_REUSED};</li>
 *   <li>the live checkout preview from cart-service with the customer's own token — <b>outside any database
 *       transaction</b>, so a slow cart never holds a connection or a lock; blocking issues → 409
 *       {@code CHECKOUT_BLOCKED}, address not the caller's → 404, cart unreachable → 503 (nothing stored);</li>
 *   <li>(Phase 11) {@code expectedCartUpdatedAt}, when present, ≠ the live cart's {@code updatedAt} → 409
 *       {@code CART_CHANGED}. Checked after the blocking issues (a cart that cannot be checked out cannot be placed,
 *       whatever the client expects) and <b>before</b> the total: a changed cart explains a changed total, and the
 *       customer has to review the items again, not just the price. Compared at the cart's own precision
 *       (milliseconds), exactly like cart-service matches {@code cartUpdatedAt} when it clears the cart;</li>
 *   <li>{@code expectedTotal} ≠ live total (Phase 12: the discounted total) → 409 {@code TOTAL_CHANGED};</li>
 *   <li>(Phase 12 §2) the cart's coupon, if any, is redeemed at pricing-service for the pre-generated order id —
 *       outside any transaction too; refused → 409 {@code COUPON_NOT_APPLICABLE} ({@code reason}), pricing
 *       unreachable → 503, and no order is placed. A redemption whose order is never saved (a later failure, a
 *       concurrent duplicate that lost the key) stays RESERVED until pricing-service releases it after 30 minutes;</li>
 *   <li>one transaction ({@link OrderCreator}): claim the key with the pre-generated order id and insert the order,
 *       lines, history and outbox events. Two parallel requests with the same key are serialised by the key's
 *       primary key: the second one gets the first one's order.</li>
 * </ol>
 * Keys are scoped per customer ({@code order:<customerId>}).
 */
@Service
public class OrderPlacementService {

    static final String SCOPE_PREFIX = "order:";
    static final int NUMBER_ATTEMPTS = 5;
    static final String ORDER_NUMBER_KEY = "uk_customer_order_number";
    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    /** The order and whether it is the answer to a retry ({@code Idempotent-Replayed: true}). */
    public record Placed(OrderView order, boolean replayed) {
    }

    /**
     * The part of the request that identifies it (the Idempotency-Key's request hash). {@code expectedCartUpdatedAt}
     * (Phase 11, at millisecond precision) is left out of the JSON when absent, so a body without it hashes exactly as
     * before.
     */
    record Fingerprint(UUID addressId, BigDecimal expectedTotal, String note,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Instant expectedCartUpdatedAt) {
    }

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final IdempotencyKeys idempotencyKeys;
    private final CartClient cart;
    private final PricingClient pricing;
    private final OrderCreator creator;
    private final OrderQueryService queries;
    private final OrderNumbers numbers;
    private final Validator validator;
    private final OrderProperties properties;
    private final OrderMetrics metrics;
    private final Clock clock;

    public OrderPlacementService(IdempotencyKeys idempotencyKeys, CartClient cart, PricingClient pricing,
                                 OrderCreator creator, OrderQueryService queries, OrderNumbers numbers,
                                 Validator validator, OrderProperties properties, OrderMetrics metrics, Clock clock) {
        this.idempotencyKeys = idempotencyKeys;
        this.cart = cart;
        this.pricing = pricing;
        this.creator = creator;
        this.queries = queries;
        this.numbers = numbers;
        this.validator = validator;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Places the order (see the class comment); timed as {@code grocery_orders_placement_seconds{outcome}}
     * ({@link OrderMetrics}): PLACED, REPLAYED, REJECTED (4xx), UNAVAILABLE (503) or FAILED.
     */
    public Placed place(UUID customerId, String idempotencyKeyHeader, PlaceOrderRequest request,
                        CallContext context) {
        long started = System.nanoTime();
        String outcome = OrderMetrics.FAILED;
        try {
            Placed placed = placeTimed(customerId, idempotencyKeyHeader, request, context);
            outcome = placed.replayed() ? OrderMetrics.REPLAYED : OrderMetrics.PLACED;
            return placed;
        } catch (ApiException e) {
            outcome = e.status().is4xxClientError() ? OrderMetrics.REJECTED
                    : e.status().value() == 503 ? OrderMetrics.UNAVAILABLE : OrderMetrics.FAILED;
            throw e;
        } finally {
            metrics.placement(outcome, System.nanoTime() - started);
        }
    }

    private Placed placeTimed(UUID customerId, String idempotencyKeyHeader, PlaceOrderRequest request,
                              CallContext context) {
        String key = IdempotencyKeys.requireKey(idempotencyKeyHeader);
        Fingerprint fingerprint = validated(request);
        String scope = SCOPE_PREFIX + customerId;
        String hash = idempotencyKeys.hash(fingerprint);

        Optional<String> earlier = idempotencyKeys.find(scope, key, hash);
        if (earlier.isPresent()) {
            log.debug("Idempotency-Key {} of customer {} replayed: order {}", key, customerId, earlier.get());
            return replay(earlier.get());
        }

        CartPreview preview = livePreview(context, fingerprint.addressId());
        if (!preview.checkoutAllowed()) {
            throw OrderException.checkoutBlocked(preview.issuesOrEmpty());
        }
        requireComplete(preview);
        if (fingerprint.expectedCartUpdatedAt() != null
                && !fingerprint.expectedCartUpdatedAt().equals(cartPrecision(preview.updatedAt()))) {
            throw OrderException.cartChanged(fingerprint.expectedCartUpdatedAt(), preview.updatedAt());
        }
        if (fingerprint.expectedTotal().compareTo(preview.total()) != 0) {
            throw OrderException.totalChanged(fingerprint.expectedTotal(), money(preview.total()));
        }

        // the id is fixed before the coupon is redeemed for it (pricing-service is idempotent per order id); a taken
        // order number below is retried with a new number, never with a new id
        UUID orderId = Ids.newId();
        CustomerOrder.Coupon coupon = redeemCoupon(context, orderId, customerId, preview,
                fingerprint.expectedTotal());

        for (int attempt = 1; ; attempt++) {
            Instant now = clock.instant();
            CustomerOrder.Draft draft = draft(orderId, numbers.next(), customerId, preview, coupon,
                    fingerprint.note(), now.plus(properties.getPaymentWindow()));
            try {
                Optional<String> claimed = creator.create(scope, key, hash, draft, now);
                if (claimed.isPresent()) {
                    return replay(claimed.get());
                }
                return new Placed(queries.view(draft.id()), false);
            } catch (DataIntegrityViolationException e) {
                if (!isOrderNumberCollision(e) || attempt >= NUMBER_ATTEMPTS) {
                    throw e;
                }
                log.warn("Order number {} is taken; retrying with a new one (attempt {})", draft.orderNumber(),
                        attempt);
            }
        }
    }

    private Placed replay(String orderId) {
        return new Placed(queries.view(UUID.fromString(orderId)), true);
    }

    /** Bean Validation of the body (after the key check, as the contract orders it), then normalised. */
    private Fingerprint validated(PlaceOrderRequest request) {
        if (request == null) {
            throw OrderException.validation(List.of(Map.of("field", "expectedTotal", "message", "must not be null")));
        }
        List<Map<String, String>> errors = validator.validate(request).stream()
                .sorted(Comparator.comparing((ConstraintViolation<PlaceOrderRequest> v) -> v.getPropertyPath().toString()))
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        if (!errors.isEmpty()) {
            throw OrderException.validation(errors);
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        return new Fingerprint(request.addressId(), money(request.expectedTotal()), note,
                cartPrecision(request.expectedCartUpdatedAt()));
    }

    /** The cart's own precision: its {@code updatedAt} is kept in milliseconds (cart-service's CartConfig). */
    private static Instant cartPrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Phase 12 §2: redeems the cart's coupon for {@code orderId} at pricing-service — the last step before the order
     * is saved. Without a coupon: {@link CustomerOrder.Coupon#NONE}, nothing is called. A coupon the cart reports
     * invalid, or that pricing-service refuses → 409 COUPON_NOT_APPLICABLE ({@code reason}); pricing unreachable →
     * 503 DEPENDENCY_UNAVAILABLE; either way no order is placed. The redeemed amounts are the order's; when they give
     * a total other than the one the customer saw (the coupon changed since the preview) → 409 TOTAL_CHANGED.
     */
    private CustomerOrder.Coupon redeemCoupon(CallContext context, UUID orderId, UUID customerId, CartPreview preview,
                                              BigDecimal expectedTotal) {
        if (!preview.hasCoupon()) {
            return CustomerOrder.Coupon.NONE;
        }
        CartPreview.Coupon applied = preview.coupon();
        if (Boolean.FALSE.equals(applied.valid())) {
            throw OrderException.couponNotApplicable(applied.reason(), null);
        }
        String code = applied.code().strip();
        BigDecimal subtotal = money(preview.subtotal());
        BigDecimal deliveryFee = money(preview.delivery().fee());
        PricingClient.RedeemRequest request = new PricingClient.RedeemRequest(code, orderId, customerId,
                preview.store().id(), preview.store().type(), subtotal, deliveryFee);
        PricingClient.Redeemed redeemed = switch (pricing.redeem(context, request)) {
            case Outcome.Ok<PricingClient.Redemption>(PricingClient.Redeemed ok) -> ok;
            case Outcome.Ok<PricingClient.Redemption>(PricingClient.Refused refused) ->
                    throw OrderException.couponNotApplicable(refused.reason(), refused.minOrderSubtotal());
            case Outcome.NotFound<PricingClient.Redemption> ignored ->
                    throw OrderException.dependencyUnavailable(Dependency.PRICING.id());
            case Outcome.Failed<PricingClient.Redemption>(String dependency) ->
                    throw OrderException.dependencyUnavailable(dependency);
        };
        BigDecimal discount = redeemed.discount() == null ? ZERO : money(redeemed.discount());
        BigDecimal deliveryFeeDiscount = redeemed.deliveryFeeDiscount() == null
                ? ZERO : money(redeemed.deliveryFeeDiscount());
        if (discount.signum() < 0 || deliveryFeeDiscount.signum() < 0 || discount.compareTo(subtotal) > 0
                || deliveryFeeDiscount.compareTo(deliveryFee) > 0) {
            log.error("pricing-service redeemed coupon {} for order {} with discounts {} / {} outside subtotal {} / "
                    + "fee {}", code, orderId, discount, deliveryFeeDiscount, subtotal, deliveryFee);
            throw OrderException.dependencyUnavailable(Dependency.PRICING.id());
        }
        BigDecimal total = subtotal.add(deliveryFee).subtract(discount).subtract(deliveryFeeDiscount);
        if (total.compareTo(expectedTotal) != 0) {
            log.warn("Coupon {} redeemed for order {} gives total {}, the customer saw {}", code, orderId, total,
                    expectedTotal);
            throw OrderException.totalChanged(expectedTotal, total);
        }
        log.info("Coupon {} redeemed for order {} (redemption {}): discount {}, delivery fee discount {}", code,
                orderId, redeemed.redemptionId(), discount, deliveryFeeDiscount);
        // the name and the funding only label the order's events for the reports, so an odd value is dropped rather
        // than failing a placement whose coupon is already reserved
        String name = redeemed.couponName() == null || redeemed.couponName().isBlank() ? null
                : redeemed.couponName().strip();
        String fundedBy = "PLATFORM".equals(redeemed.fundedBy()) || "STORE".equals(redeemed.fundedBy())
                ? redeemed.fundedBy() : null;
        return new CustomerOrder.Coupon(code, redeemed.redemptionId(), discount, deliveryFeeDiscount, name, fundedBy);
    }

    private CartPreview livePreview(CallContext context, UUID addressId) {
        return switch (cart.preview(context, addressId)) {
            case Outcome.Ok<CartPreview>(CartPreview preview) -> preview;
            case Outcome.NotFound<CartPreview> ignored -> throw OrderException.addressNotFound();
            case Outcome.Failed<CartPreview>(String dependency) -> throw OrderException.dependencyUnavailable(dependency);
        };
    }

    /** A preview without blocking issues must carry everything an order is made of; otherwise cart misbehaves. */
    private static void requireComplete(CartPreview preview) {
        List<String> missing = new ArrayList<>();
        if (preview.store() == null || preview.store().id() == null || orderType(preview.store().type()) == null) {
            missing.add("store");
        }
        if (preview.lines() == null || preview.lines().isEmpty()) {
            missing.add("lines");
        } else {
            for (CartPreview.Line line : preview.lines()) {
                if (line.itemId() == null || itemType(line.type()) == null || line.name() == null
                        || line.quantity() == null || line.quantity() < 1 || line.unitPrice() == null
                        || line.lineTotal() == null) {
                    missing.add("lines[" + line.itemId() + "]");
                }
            }
        }
        if (preview.subtotal() == null || preview.total() == null) {
            missing.add("totals");
        }
        if (preview.address() == null) {
            missing.add("address");
        }
        if (preview.delivery() == null || preview.delivery().fee() == null) {
            missing.add("delivery");
        }
        if (!missing.isEmpty()) {
            log.error("cart-service preview allows checkout but lacks {}", missing);
            throw OrderException.dependencyUnavailable(Dependency.CART.id());
        }
    }

    private static CustomerOrder.Draft draft(UUID orderId, String orderNumber, UUID customerId, CartPreview preview,
                                             CustomerOrder.Coupon coupon, String note, Instant paymentDueAt) {
        List<OrderItem> items = preview.lines().stream()
                .map(line -> new OrderItem(line.itemId(), itemType(line.type()), line.name(), line.unitLabel(),
                        line.imageUrl(), line.foodType(), line.quantity(), money(line.unitPrice()),
                        line.mrp() == null ? null : money(line.mrp()), money(line.lineTotal())))
                .toList();
        int itemCount = preview.itemCount() != null
                ? preview.itemCount()
                : items.stream().mapToInt(OrderItem::getQuantity).sum();
        BigDecimal subtotal = money(preview.subtotal());
        BigDecimal mrpTotal = preview.mrpTotal() == null ? subtotal : money(preview.mrpTotal());
        BigDecimal savings = preview.savings() == null ? mrpTotal.subtract(subtotal) : money(preview.savings());
        CartPreview.Address address = preview.address();
        DeliveryAddress deliveryAddress = new DeliveryAddress(address.id(), address.label(), address.recipientName(),
                address.phone(), address.line1(), address.line2(), address.landmark(), address.city(),
                address.state(), address.postalCode(), address.latitude(), address.longitude());
        BigDecimal distance = preview.delivery().distanceKm() == null
                ? null
                : preview.delivery().distanceKm().setScale(1, RoundingMode.HALF_UP);
        CartPreview.Store store = preview.store();
        // the pickup point (Phase 9): both coordinates or none, so a half-known location is never sent on
        boolean located = store.latitude() != null && store.longitude() != null;
        return new CustomerOrder.Draft(orderId, orderNumber, customerId, orderType(store.type()), store.id(),
                store.name(), store.city(), located ? store.latitude() : null, located ? store.longitude() : null,
                items, itemCount, subtotal, mrpTotal, savings, money(preview.delivery().fee()),
                money(preview.total()), deliveryAddress, distance, note, preview.updatedAt(), paymentDueAt, coupon);
    }

    static boolean isOrderNumberCollision(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(ORDER_NUMBER_KEY)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static OrderType orderType(String value) {
        try {
            return value == null ? null : OrderType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ItemType itemType(String value) {
        try {
            return value == null ? null : ItemType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
