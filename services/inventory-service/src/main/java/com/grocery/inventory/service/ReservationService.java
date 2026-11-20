package com.grocery.inventory.service;

import com.grocery.common.id.Ids;
import com.grocery.common.web.ConflictException;
import com.grocery.common.web.NotFoundException;
import com.grocery.inventory.entity.MovementType;
import com.grocery.inventory.entity.ProductRef;
import com.grocery.inventory.entity.ReservationStatus;
import com.grocery.inventory.entity.ReservedItem;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.entity.StockMovement;
import com.grocery.inventory.entity.StockReservation;
import com.grocery.inventory.repository.ProductRefRepository;
import com.grocery.inventory.repository.StockReservationRepository;
import com.grocery.inventory.service.ReservationFailedException.UnavailableItem;
import com.grocery.inventory.service.StockEventPublisher.ReleaseReason;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stock held for orders (Phase 4 contract §2 "Reservations", Phase 6 §6 "Inventory", Phase 9 §7). A domain service
 * without REST endpoints: the order saga drives it ({@code OrderCreated} → reserve, {@code OrderConfirmed} → hold
 * until pickup, {@code OrderCancelled} → release, {@code OrderOutForDelivery} → commit, see
 * {@link OrderStockService}), and the expiry poller releases what was never paid ({@link ReservationExpiryPoller}).
 *
 * <ul>
 *   <li>{@link #reserve}: all or nothing and idempotent per order id — the same order id returns the existing
 *       reservation (whatever its status) and never holds stock twice. Insufficient, unknown or archived products
 *       → {@link ReservationFailedException} listing them, nothing changed.</li>
 *   <li>{@link #holdUntilPickup}: a RESERVED reservation no longer expires ({@code expires_at} = NULL).</li>
 *   <li>{@link #release}: gives the held stock back (RESERVED → RELEASED). Idempotent; an unknown order is a
 *       no-op. <b>Releasing a COMMITTED reservation is a no-op</b> that returns it unchanged: the goods have left
 *       the store, and putting them back is a vendor adjustment (INCREASE, reason RETURN).</li>
 *   <li>{@link #releaseExpired}: one batch of the expiry poller (RESERVED → RELEASED past {@code expires_at}).</li>
 *   <li>{@link #commit}: removes the held stock from on hand and reserved (RESERVED → COMMITTED) and publishes
 *       {@code StockCommitted}. Idempotent; an unknown order is 404 RESERVATION_NOT_FOUND, a RELEASED one 409
 *       RESERVATION_RELEASED (its stock may already be sold to someone else). The saga's variant
 *       ({@code OrderOutForDelivery} at pickup, Phase 9) treats both as "nothing to commit".</li>
 * </ul>
 * Every stock change follows the locking rule of {@link StockLocks}: the stock rows (ascending product id) are
 * locked before anything else is read in the changing transaction. Release and commit learn which rows to lock
 * from the reservation's lines in a preceding read (the lines never change), then re-read the reservation itself
 * with {@code FOR UPDATE} after the stock locks. The expiry batch is the one documented exception to "stock rows
 * first" (see {@link #releaseExpired}). Movements: RESERVE / RELEASE (delta 0) and COMMIT (negative delta),
 * {@code reference} = order id, no actor; availability and low-stock events on transitions.
 *
 * <p><b>Reservation events</b> ({@code inventory.events}, aggregate {@code Reservation}, key orderId) are written
 * to the outbox in the same transaction as the change they describe, and only by the call that made it:
 * {@code StockReserved} when a reservation is created, {@code StockReleased} when stock is given back
 * ({@code ORDER_CANCELLED} or {@code RESERVATION_EXPIRED}, the latter with {@code StockReservationExpired}),
 * {@code StockCommitted} when it is committed. A call that finds the change already made publishes nothing. {@code StockReservationFailed} changes no stock and
 * is written by {@link OrderStockService}.
 */
@Service
public class ReservationService {

    /** A product and the quantity to hold for it. */
    public record ReservationLine(UUID productId, int quantity) {
    }

    /** @param expiresAt null once the order is confirmed (held until pickup) */
    public record ReservationView(UUID reservationId, UUID orderId, ReservationStatus status, Instant expiresAt,
                                  List<ReservationLine> items) {
    }

    /**
     * What a call did: the order's reservation afterwards (null when there is none, or when the {@link Guard}
     * skipped the call) and whether THIS call changed it — and therefore published its event.
     */
    public record Outcome(ReservationView reservation, boolean changed) {

        static final Outcome NOTHING = new Outcome(null, false);

        static Outcome unchanged(StockReservation reservation) {
            return new Outcome(view(reservation), false);
        }

        static Outcome changed(StockReservation reservation) {
            return new Outcome(view(reservation), true);
        }
    }

    /**
     * Runs first inside the changing transaction, so it commits or rolls back with the change. The order-event
     * consumer marks the event processed here; {@code false} (already processed) = the call does nothing. It runs
     * only when there is something to do (not for a reservation that already exists / an unknown order).
     */
    @FunctionalInterface
    public interface Guard {
        Guard NONE = () -> true;

        boolean proceed();
    }

    private record Located(UUID reservationId, List<UUID> productIds) {
    }

    private final StockLocks locks;
    private final StockReservationRepository reservations;
    private final ProductRefRepository productRefs;
    private final StockEventPublisher events;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate expiryTransaction;
    private final Clock clock;

    public ReservationService(StockLocks locks, StockReservationRepository reservations,
                              ProductRefRepository productRefs, StockEventPublisher events,
                              EntityManager entityManager, PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.locks = locks;
        this.reservations = reservations;
        this.productRefs = productRefs;
        this.events = events;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        // READ COMMITTED: the claim's FOR UPDATE SKIP LOCKED locks only the rows it returns (no gap locks)
        this.expiryTransaction = new TransactionTemplate(transactionManager);
        this.expiryTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    /**
     * Holds {@code quantity} of every line for {@code orderId} until {@code ttl} from now. Lines for the same
     * product are added together. Publishes {@code StockReserved} when the reservation is created.
     *
     * @throws ReservationFailedException if any product lacks stock (nothing changed)
     */
    public ReservationView reserve(UUID orderId, List<ReservationLine> lines, Duration ttl) {
        return reserve(orderId, lines, ttl, Guard.NONE).reservation();
    }

    /**
     * {@link #reserve(UUID, List, Duration)} with a {@link Guard} in the reserving transaction. {@code changed}
     * is true only when this call created the reservation (and published {@code StockReserved}).
     *
     * @throws ReservationFailedException if any product lacks stock (nothing changed, the guard rolled back too)
     */
    public Outcome reserve(UUID orderId, List<ReservationLine> lines, Duration ttl, Guard guard) {
        Objects.requireNonNull(orderId, "orderId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A reservation needs at least one line");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (ReservationLine line : lines) {
            if (line.productId() == null || line.quantity() <= 0) {
                throw new IllegalArgumentException("Every line needs a product id and a positive quantity");
            }
            wanted.merge(line.productId(), line.quantity(), Math::addExact);
        }

        Optional<ReservationView> existing = find(orderId);       // fast path for redeliveries, no locks
        if (existing.isPresent()) {
            return new Outcome(existing.get(), false);
        }
        try {
            return transaction.execute(status -> guard.proceed() ? doReserve(orderId, wanted, ttl) : Outcome.NOTHING);
        } catch (DataIntegrityViolationException e) {
            // The same order was reserved at the same moment with other products (so the stock locks did not
            // serialise the two calls): the unique key on order_id let exactly one win. Return the winner.
            return new Outcome(find(orderId).orElseThrow(() -> e), false);
        }
    }

    private Outcome doReserve(UUID orderId, Map<UUID, Integer> wanted, Duration ttl) {
        Map<UUID, StockItem> locked = locks.lockAll(wanted.keySet());                 // 1. lock first
        // Read after the locks: a concurrent reserve of the same order (same products) has committed by now.
        Optional<StockReservation> existing = reservations.findByOrderId(orderId);
        if (existing.isPresent()) {
            return Outcome.unchanged(existing.get());
        }
        Map<UUID, ProductRef> products = productRefs.findAllById(locked.keySet()).stream()
                .collect(Collectors.toMap(ProductRef::getProductId, Function.identity()));

        List<UnavailableItem> unavailable = new ArrayList<>();
        wanted.forEach((productId, quantity) -> {
            StockItem stock = locked.get(productId);
            ProductRef product = products.get(productId);
            int available = stock == null || product == null || product.isArchived() ? 0 : stock.available();
            if (quantity > available) {
                unavailable.add(new UnavailableItem(productId, quantity, available));
            }
        });
        if (!unavailable.isEmpty()) {
            throw new ReservationFailedException(orderId, unavailable);              // nothing changed
        }

        Instant now = clock.instant();
        List<ReservedItem> items = wanted.entrySet().stream()
                .map(e -> new ReservedItem(e.getKey(), locked.get(e.getKey()).getVendorId(), e.getValue()))
                .toList();
        StockReservation reservation = new StockReservation(Ids.newId(), orderId, items, now.plus(ttl), now);
        entityManager.persist(reservation);
        for (StockItem stock : locked.values()) {                                    // 2.+3. ascending id order
            int availableBefore = stock.available();
            stock.reserve(wanted.get(stock.getProductId()), now);
            record(stock, MovementType.RESERVE, 0, orderId, now);
            events.transitions(stock, availableBefore, () -> products.get(stock.getProductId()).getName());
        }
        reservations.flush();
        events.reserved(reservation);
        return Outcome.changed(reservation);
    }

    /**
     * The order was paid ({@code OrderConfirmed}): a RESERVED reservation no longer expires ({@code expires_at}
     * = NULL) and stays held until it is committed or released. No stock changes, so only the reservation row
     * is locked (by primary key, one lock: no lock-order cycle is possible). Released / committed reservations
     * and one already held are left alone; an order without a reservation is a no-op. Publishes nothing.
     */
    public Outcome holdUntilPickup(UUID orderId, Guard guard) {
        Optional<Located> located = locate(orderId);
        if (located.isEmpty()) {
            return Outcome.NOTHING;
        }
        return transaction.execute(status -> {
            if (!guard.proceed()) {
                return Outcome.NOTHING;
            }
            StockReservation reservation = reservations.lockById(located.get().reservationId()).orElseThrow();
            if (reservation.getStatus() != ReservationStatus.RESERVED || reservation.getExpiresAt() == null) {
                return Outcome.unchanged(reservation);
            }
            reservation.holdUntilPickup(clock.instant());
            reservations.flush();
            return Outcome.changed(reservation);
        });
    }

    /** Gives the held stock back ({@code ORDER_CANCELLED}). Empty if nothing was ever reserved for the order. */
    public Optional<ReservationView> release(UUID orderId) {
        return Optional.ofNullable(release(orderId, Guard.NONE).reservation());
    }

    /**
     * Gives the held stock back and publishes {@code StockReleased(ORDER_CANCELLED)} — only when this call
     * released it ({@code changed}). Unknown order: nothing, the guard does not run.
     */
    public Outcome release(UUID orderId, Guard guard) {
        Optional<Located> located = locate(orderId);
        if (located.isEmpty()) {
            return Outcome.NOTHING;
        }
        return transaction.execute(status -> {
            if (!guard.proceed()) {
                return Outcome.NOTHING;
            }
            Map<UUID, StockItem> locked = lockLines(located.get());                   // 1. lock first
            StockReservation reservation = reservations.lockById(located.get().reservationId()).orElseThrow();
            if (reservation.getStatus() != ReservationStatus.RESERVED) {
                return Outcome.unchanged(reservation);  // RELEASED: already given back; COMMITTED: nothing to give
            }
            giveBack(reservation, locked, clock.instant());
            events.released(reservation, ReleaseReason.ORDER_CANCELLED);
            return Outcome.changed(reservation);
        });
    }

    /**
     * One batch of the expiry poller: releases up to {@code limit} RESERVED reservations whose {@code expires_at}
     * is before {@code now}, each with {@code StockReleased(RESERVATION_EXPIRED)} + {@code StockReservationExpired}.
     * Returns how many were claimed (= released).
     *
     * <p><b>Lock order.</b> One READ COMMITTED transaction:
     * <ol>
     *   <li>claim the batch: {@code SELECT … FOR UPDATE SKIP LOCKED} on the reservations (by the status/expiry
     *       index, record locks only). Rows locked by another poller replica — or by a confirm or release in
     *       flight — are skipped, so replicas work on disjoint batches and never wait for each other here;</li>
     *   <li>lock the stock rows of <em>all</em> claimed lines at once, in ascending product id order
     *       ({@link StockLocks#lockAll}) — stock rows are still locked before they are changed, and never in two
     *       separate rounds (which could cross another transaction's ascending order);</li>
     *   <li>give the stock back, write the movements and the events; commit releases everything.</li>
     * </ol>
     * The claimed reservations are locked before the stock rows, the opposite of {@link #release} /
     * {@link #commit} (stock rows, then the reservation). That is deliberate: claiming first is what lets
     * {@code SKIP LOCKED} spread the work over replicas (claiming after the stock locks would make replicas pick
     * the same candidates and queue on the same stock rows). The only cycle this allows is a release/commit of
     * <em>the same order</em> in the same instant as its expiry: InnoDB detects the deadlock at once and rolls one
     * back. A poller victim retries in its next run and finds the reservation released; a Kafka victim is retried
     * (deadlocks are transient failures) and finds it released by the expiry, so it publishes nothing. Adjustments,
     * reserves and other releases only lock stock rows (ascending) and insert new reservations, which the
     * claim's record locks never block. Two pollers hold disjoint reservations and take stock rows ascending, so
     * they cannot deadlock either.
     */
    public int releaseExpired(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Integer released = expiryTransaction.execute(status -> {
            List<UUID> ids = reservations.claimExpired(now, limit).stream().map(UUID::fromString).toList();
            if (ids.isEmpty()) {
                return 0;
            }
            // Locked by the claim: their status and expiry cannot change before this transaction ends
            List<StockReservation> claimed = reservations.findAllById(ids).stream()
                    .sorted(Comparator.comparing(StockReservation::getExpiresAt)
                            .thenComparing(StockReservation::getId, StockLocks.BINARY_ORDER))
                    .toList();
            Set<UUID> productIds = new LinkedHashSet<>();
            claimed.forEach(r -> r.getItems().forEach(item -> productIds.add(item.getProductId())));
            Map<UUID, StockItem> locked = locks.lockAll(productIds);                  // ascending, all at once
            if (locked.size() != productIds.size()) {
                throw new IllegalStateException("Stock rows missing for expired reservations " + ids);
            }
            Instant changedAt = clock.instant();
            for (StockReservation reservation : claimed) {
                giveBack(reservation, locked, changedAt);
                events.released(reservation, ReleaseReason.RESERVATION_EXPIRED);
                events.expired(reservation);
            }
            return claimed.size();
        });
        return released == null ? 0 : released;
    }

    /**
     * The held stock left the store: on hand and reserved both go down by the reserved quantities, and
     * {@code StockCommitted} is published in the same transaction. Idempotent (a COMMITTED reservation is returned
     * unchanged, nothing published).
     *
     * @throws NotFoundException RESERVATION_NOT_FOUND for an order without a reservation
     * @throws ConflictException RESERVATION_RELEASED when its stock was already given back
     */
    public ReservationView commit(UUID orderId) {
        Located located = locate(orderId).orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND",
                "No stock is reserved for order " + orderId));
        return transaction.execute(status -> doCommit(orderId, located, true)).reservation();
    }

    /**
     * {@link #commit(UUID)} for the order saga (Phase 9, {@code OrderOutForDelivery}) with a {@link Guard} in the
     * committing transaction, and lenient: an unknown order is nothing (the guard does not run), a RELEASED or
     * already COMMITTED reservation is returned unchanged (the guard ran, nothing is published). {@code changed} is
     * true only when this call committed the stock and published {@code StockCommitted}.
     */
    public Outcome commit(UUID orderId, Guard guard) {
        Optional<Located> located = locate(orderId);
        if (located.isEmpty()) {
            return Outcome.NOTHING;
        }
        return transaction.execute(status -> guard.proceed()
                ? doCommit(orderId, located.get(), false)
                : Outcome.NOTHING);
    }

    private Outcome doCommit(UUID orderId, Located located, boolean failIfReleased) {
        Map<UUID, StockItem> locked = lockLines(located);                           // 1. lock first
        StockReservation reservation = reservations.lockById(located.reservationId()).orElseThrow();
        if (reservation.getStatus() == ReservationStatus.COMMITTED) {
            return Outcome.unchanged(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            if (failIfReleased) {
                throw new ConflictException("RESERVATION_RELEASED",
                        "The stock reserved for order " + orderId + " was already released");
            }
            return Outcome.unchanged(reservation);
        }
        Instant now = clock.instant();
        for (ReservedItem item : sorted(reservation.getItems())) {
            StockItem stock = locked.get(item.getProductId());
            int availableBefore = stock.available();
            stock.commit(item.getQuantity(), now);
            record(stock, MovementType.COMMIT, -item.getQuantity(), orderId, now);
            events.transitions(stock, availableBefore, () -> productName(stock.getProductId()));
        }
        reservation.markCommitted(now);
        reservations.flush();
        events.committed(reservation, now);
        return Outcome.changed(reservation);
    }

    /** The reservation of an order, if any (read only). */
    public Optional<ReservationView> find(UUID orderId) {
        return readTransaction.execute(status -> reservations.findByOrderId(orderId).map(ReservationService::view));
    }

    /** RESERVED → RELEASED on stock rows the caller has locked: reserved − q per line, movements, transitions. */
    private void giveBack(StockReservation reservation, Map<UUID, StockItem> locked, Instant now) {
        for (ReservedItem item : sorted(reservation.getItems())) {
            StockItem stock = locked.get(item.getProductId());
            int availableBefore = stock.available();
            stock.release(item.getQuantity(), now);
            record(stock, MovementType.RELEASE, 0, reservation.getOrderId(), now);
            events.transitions(stock, availableBefore, () -> productName(stock.getProductId()));
        }
        reservation.markReleased(now);
        reservations.flush();
    }

    /** Which stock rows a release/commit must lock. The lines never change, so reading them first is safe. */
    private Optional<Located> locate(UUID orderId) {
        return readTransaction.execute(status -> reservations.findByOrderId(orderId)
                .map(r -> new Located(r.getId(), r.getItems().stream().map(ReservedItem::getProductId).toList())));
    }

    private Map<UUID, StockItem> lockLines(Located located) {
        Map<UUID, StockItem> locked = locks.lockAll(located.productIds());
        if (locked.size() != located.productIds().size()) {
            throw new IllegalStateException("Stock rows missing for reservation " + located.reservationId());
        }
        return locked;
    }

    private void record(StockItem stock, MovementType type, int delta, UUID orderId, Instant now) {
        entityManager.persist(StockMovement.record(Ids.newId(), stock, type, delta, null, null,
                orderId.toString(), null, now));
    }

    private String productName(UUID productId) {
        return productRefs.findById(productId).map(ProductRef::getName).orElse(null);
    }

    private static List<ReservedItem> sorted(List<ReservedItem> items) {
        return items.stream().sorted((a, b) -> StockLocks.BINARY_ORDER.compare(a.getProductId(), b.getProductId()))
                .toList();
    }

    private static ReservationView view(StockReservation reservation) {
        return new ReservationView(reservation.getId(), reservation.getOrderId(), reservation.getStatus(),
                reservation.getExpiresAt(), reservation.getItems().stream()
                .map(i -> new ReservationLine(i.getProductId(), i.getQuantity()))
                .toList());
    }
}
