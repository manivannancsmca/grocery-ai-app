package com.grocery.vendor.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.vendor.config.FulfillmentProperties;
import com.grocery.vendor.dto.StoreOrderDtos.StoreOrderView;
import com.grocery.vendor.dto.StoreOrderExports;
import com.grocery.vendor.entity.RejectionReason;
import com.grocery.vendor.entity.StoreOrderStatus;
import com.grocery.vendor.entity.VendorOrder;
import com.grocery.vendor.mapper.StoreOrderMapper;
import com.grocery.vendor.repository.VendorOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The vendor's order board (contract §2): list, detail, accept / reject / packing / ready, and the auto-reject batch.
 *
 * <h2>Locking rule (every transition)</h2>
 * A store user's action, the auto-reject poller and {@code OrderCancelled} ({@link StoreOrderProjection}) can reach
 * the same order at the same moment. Each of them, in one transaction:
 * <ol>
 *   <li><b>locks first:</b> {@code SELECT … FOR UPDATE} on the {@code vendor_order} row (the poller claims with
 *       {@code FOR UPDATE SKIP LOCKED}) before reading anything else of the order, so the history read afterwards
 *       includes everything the previous lock holder committed;</li>
 *   <li>checks the state guard on the locked row (another store's order → 404; wrong status → 409, a repeat of the
 *       same action → 200 unchanged, nothing written);</li>
 *   <li>changes the status, appends the history entry and writes the outbox event; commit releases the lock.</li>
 * </ol>
 * Two changes of one order are therefore applied one after the other, each seeing the other's result: exactly one of
 * accept / reject / auto-reject / cancel wins a NEW order, and the loser sees its outcome. Lock waits that time out
 * become 409 {@code CONCURRENT_MODIFICATION} (grocery-common).
 */
@Service
public class StoreOrderService {

    private static final Logger log = LoggerFactory.getLogger(StoreOrderService.class);

    private final VendorOrderRepository orders;
    private final StoreOrderEventPublisher events;
    private final FulfillmentProperties properties;
    private final ExportQueries exports;
    private final Clock clock;
    private final TransactionTemplate claimTransaction;

    public StoreOrderService(VendorOrderRepository orders, StoreOrderEventPublisher events,
                             FulfillmentProperties properties, ExportQueries exports, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.events = events;
        this.properties = properties;
        this.exports = exports;
        this.clock = clock;
        // READ COMMITTED: the claim's FOR UPDATE SKIP LOCKED locks only the rows it returns (no gap locks)
        this.claimTransaction = new TransactionTemplate(transactionManager);
        this.claimTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** {@code status}: blank = all, {@code ACTIVE}, or one store status; NEW first by deadline, then newest first. */
    @Transactional(readOnly = true)
    public PageResponse<StoreOrderView> board(CurrentUser user, String status, Integer page, Integer size) {
        UUID vendorId = user.requireVendorId();
        return Pages.toResponse(orders.board(vendorId, statuses(status), Pages.request(page, size, Sort.unsorted())),
                StoreOrderMapper::toView);
    }

    /**
     * {@code GET /api/vendor/orders/export}: the caller's own orders (the vendor id is the caller's, from the JWT) with
     * the board's filter, every page, in the board's order: the NEW orders by deadline first, then the rest newest
     * first. A keyset follows one plain order, so the two parts are read one after the other, each with the board's
     * filter text and its share of the requested statuses. No transaction here: each batch has its own.
     */
    public RowSource<StoreOrderView> boardExport(CurrentUser user, String status) {
        UUID vendorId = user.requireVendorId();
        Set<StoreOrderStatus> statuses = statuses(status);
        Set<StoreOrderStatus> rest = EnumSet.copyOf(statuses);
        rest.remove(StoreOrderStatus.NEW);
        List<RowSource<StoreOrderView>> parts = new ArrayList<>(2);
        if (statuses.contains(StoreOrderStatus.NEW)) {
            parts.add(boardRows(vendorId, EnumSet.of(StoreOrderStatus.NEW), StoreOrderExports.NEW_BY_DEADLINE));
        }
        if (!rest.isEmpty()) {
            parts.add(boardRows(vendorId, rest, StoreOrderExports.NEWEST_FIRST));
        }
        return inTurn(parts);
    }

    private RowSource<StoreOrderView> boardRows(UUID vendorId, Set<StoreOrderStatus> statuses,
                                                Order<VendorOrder> order) {
        return exports.jpql(VendorOrder.class, "o", VendorOrderRepository.BOARD)
                .param("vendorId", vendorId)
                .param("statuses", statuses)
                .orderBy(order)
                .rows(StoreOrderMapper::toView);
    }

    /** The rows of {@code parts}, one part after the other (each still read in its own keyset batches). */
    private static RowSource<StoreOrderView> inTurn(List<RowSource<StoreOrderView>> parts) {
        return new RowSource<>() {
            @Override
            public long count() {
                return parts.stream().mapToLong(RowSource::count).sum();
            }

            @Override
            public void forEachBatch(int batchSize, BatchHandler<? super StoreOrderView> handler) throws IOException {
                boolean[] stopped = {false};
                for (RowSource<StoreOrderView> part : parts) {
                    part.forEachBatch(batchSize, batch -> {
                        stopped[0] = !handler.handle(batch);
                        return !stopped[0];
                    });
                    if (stopped[0]) {
                        return;
                    }
                }
            }
        };
    }

    @Transactional(readOnly = true)
    public StoreOrderView get(CurrentUser user, UUID orderId) {
        UUID vendorId = user.requireVendorId();
        return StoreOrderMapper.toView(orders.findByOrderIdAndVendorId(orderId, vendorId)
                .orElseThrow(StoreOrderException::notFound));
    }

    /** NEW → ACCEPTED (+ VendorOrderAccepted); ACCEPTED again → unchanged. */
    @Transactional
    public StoreOrderView accept(CurrentUser user, UUID orderId, Integer readyInMinutes) {
        VendorOrder order = lockOwn(user, orderId);
        if (order.getStatus() == StoreOrderStatus.ACCEPTED) {
            return StoreOrderMapper.toView(order);
        }
        if (order.getStatus() != StoreOrderStatus.NEW) {
            throw StoreOrderException.notPending(order.getStatus());
        }
        Instant now = clock.instant();
        if (order.isAcceptanceExpired(now)) {
            throw StoreOrderException.acceptanceExpired();
        }
        int minutes = readyInMinutes != null ? readyInMinutes : properties.defaultReadyInMinutes();
        order.accept(minutes, user.userId(), now);
        events.accepted(order, user.userId());
        log.info("Order {} accepted by vendor {}, ready in {} min", orderId, order.getVendorId(), minutes);
        return StoreOrderMapper.toView(order);
    }

    /** NEW → REJECTED (+ VendorOrderRejected); REJECTED again → unchanged. {@code note} required for OTHER. */
    @Transactional
    public StoreOrderView reject(CurrentUser user, UUID orderId, RejectionReason reason, String note) {
        if (reason == null || reason == RejectionReason.NO_RESPONSE) {
            throw StoreOrderException.validation("reason", "must be one of OUT_OF_STOCK, STORE_CLOSED, TOO_BUSY, OTHER");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (reason == RejectionReason.OTHER && cleanNote == null) {
            throw StoreOrderException.validation("note", "is required when the reason is OTHER");
        }
        VendorOrder order = lockOwn(user, orderId);
        if (order.getStatus() == StoreOrderStatus.REJECTED) {
            return StoreOrderMapper.toView(order);
        }
        if (order.getStatus() != StoreOrderStatus.NEW) {
            throw StoreOrderException.notPending(order.getStatus());
        }
        order.reject(reason, cleanNote, false, user.userId(), clock.instant());
        events.rejected(order);
        log.info("Order {} rejected by vendor {} ({})", orderId, order.getVendorId(), reason);
        return StoreOrderMapper.toView(order);
    }

    /** ACCEPTED → PREPARING (+ VendorOrderPreparing); PREPARING again → unchanged. */
    @Transactional
    public StoreOrderView preparing(CurrentUser user, UUID orderId) {
        VendorOrder order = lockOwn(user, orderId);
        if (order.getStatus() == StoreOrderStatus.PREPARING) {
            return StoreOrderMapper.toView(order);
        }
        if (order.getStatus() != StoreOrderStatus.ACCEPTED) {
            throw StoreOrderException.invalidTransition(order.getStatus(), StoreOrderStatus.PREPARING);
        }
        order.startPreparing(user.userId(), clock.instant());
        events.preparing(order, user.userId());
        return StoreOrderMapper.toView(order);
    }

    /** ACCEPTED / PREPARING → READY_FOR_PICKUP (+ VendorOrderReadyForPickup); READY_FOR_PICKUP again → unchanged. */
    @Transactional
    public StoreOrderView ready(CurrentUser user, UUID orderId) {
        VendorOrder order = lockOwn(user, orderId);
        if (order.getStatus() == StoreOrderStatus.READY_FOR_PICKUP) {
            return StoreOrderMapper.toView(order);
        }
        if (order.getStatus() != StoreOrderStatus.ACCEPTED && order.getStatus() != StoreOrderStatus.PREPARING) {
            throw StoreOrderException.invalidTransition(order.getStatus(), StoreOrderStatus.READY_FOR_PICKUP);
        }
        order.markReady(user.userId(), clock.instant());
        events.readyForPickup(order, user.userId());
        log.info("Order {} ready for pickup at vendor {}", orderId, order.getVendorId());
        return StoreOrderMapper.toView(order);
    }

    /**
     * One batch of the auto-reject poller, in its own READ COMMITTED transaction: claims up to {@code limit} NEW
     * orders past {@code acceptBy} ({@code FOR UPDATE SKIP LOCKED}) and rejects each with reason NO_RESPONSE,
     * {@code auto} true and no actor (+ VendorOrderRejected). Returns how many were claimed.
     */
    public int autoRejectExpired(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Integer claimed = claimTransaction.execute(status -> {
            List<UUID> ids = orders.claimExpired(now, limit).stream().map(UUID::fromString).toList();
            if (ids.isEmpty()) {
                return 0;
            }
            // Locked by the claim: their status cannot change before this transaction ends
            for (VendorOrder order : orders.findAllById(ids)) {
                if (order.getStatus() != StoreOrderStatus.NEW || !order.isAcceptanceExpired(now)) {
                    continue;                                   // cannot happen under the lock; never reject wrongly
                }
                order.reject(RejectionReason.NO_RESPONSE, null, true, null, now);
                events.rejected(order);
                log.info("Order {} auto-rejected: vendor {} did not accept it by {}", order.getOrderId(),
                        order.getVendorId(), order.getAcceptBy());
            }
            return ids.size();
        });
        return claimed == null ? 0 : claimed;
    }

    /** The caller's own order, locked; another store's or an unknown order → 404 (403 without a vendor_id claim). */
    private VendorOrder lockOwn(CurrentUser user, UUID orderId) {
        UUID vendorId = user.requireVendorId();
        return orders.lockById(orderId)
                .filter(order -> order.getVendorId().equals(vendorId))
                .orElseThrow(StoreOrderException::notFound);
    }

    /** {@code status} query parameter: blank = all, {@code ACTIVE}, or one status; anything else 400. */
    static Set<StoreOrderStatus> statuses(String status) {
        if (status == null || status.isBlank()) {
            return EnumSet.allOf(StoreOrderStatus.class);
        }
        if ("ACTIVE".equals(status.trim())) {
            return StoreOrderStatus.ACTIVE;
        }
        return EnumSet.of(BadRequestException.parseEnum(StoreOrderStatus.class, "status", status));
    }
}
