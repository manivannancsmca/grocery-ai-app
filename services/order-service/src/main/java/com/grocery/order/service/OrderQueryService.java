package com.grocery.order.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.order.config.OrderProperties;
import com.grocery.order.dto.OrderDtos.AdminOrderSummary;
import com.grocery.order.dto.OrderDtos.AdminOrderView;
import com.grocery.order.dto.OrderDtos.OrderEventView;
import com.grocery.order.dto.OrderDtos.OrderStats;
import com.grocery.order.dto.OrderDtos.OrderSummary;
import com.grocery.order.dto.OrderDtos.OrderView;
import com.grocery.order.dto.OrderDtos.StuckOrderView;
import com.grocery.order.dto.OrderDtos.StuckStoreView;
import com.grocery.order.dto.OrderExports;
import com.grocery.order.dto.OrderMapper;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.OrderType;
import com.grocery.order.repository.CustomerOrderRepository;
import com.grocery.order.repository.OrderEventLog;
import com.grocery.order.repository.OrderSpecifications;
import com.grocery.order.repository.StuckOrders;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The read side (contract §2 lists and detail, §4 admin monitor and stats; Phase 10 the stuck orders and the order
 * event timeline). Plain consistent reads in read-only
 * transactions, no locks: a read never waits for a status change in flight and sees the last committed state.
 * Lists are newest first ({@code createdAt}, then id — UUIDv7, so also creation order).
 */
@Service
public class OrderQueryService {

    static final String OPEN = "OPEN";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** The admin list's filters; every one optional (null = no filter). {@code from}/{@code to} are inclusive dates. */
    public record AdminFilter(String status, String type, UUID customerId, UUID storeId, String q, LocalDate from,
                              LocalDate to) {
    }

    private final CustomerOrderRepository orders;
    private final StuckOrders stuckOrders;
    private final OrderEventLog eventLog;
    private final OrderProperties properties;
    private final ReturnQueryService returns;
    private final ExportQueries exports;
    private final Clock clock;

    public OrderQueryService(CustomerOrderRepository orders, StuckOrders stuckOrders, OrderEventLog eventLog,
                             OrderProperties properties, ReturnQueryService returns, ExportQueries exports,
                             Clock clock) {
        this.orders = orders;
        this.stuckOrders = stuckOrders;
        this.eventLog = eventLog;
        this.properties = properties;
        this.returns = returns;
        this.exports = exports;
        this.clock = clock;
    }

    /** Any order (used after creating one and to answer a replayed {@code POST /api/orders}). */
    @Transactional(readOnly = true)
    public OrderView view(UUID orderId) {
        return withReturns(orders.findById(orderId).orElseThrow(OrderException::orderNotFound));
    }

    /** The customer's own order; another customer's is 404 {@code ORDER_NOT_FOUND}, like an unknown one. */
    @Transactional(readOnly = true)
    public OrderView customerOrder(UUID customerId, UUID orderId) {
        return withReturns(orders.findById(orderId)
                .filter(order -> order.getCustomerId().equals(customerId))
                .orElseThrow(OrderException::orderNotFound));
    }

    /**
     * The customer's own order by its number (any case, e.g. "ga-7k3f9q2m"); another customer's is 404
     * {@code ORDER_NOT_FOUND}, like an unknown one. The MCP tool {@code getOrderStatus} (OrderMcpTools) answers with it.
     */
    @Transactional(readOnly = true)
    public OrderView customerOrderByNumber(UUID customerId, String orderNumber) {
        String number = orderNumber == null ? "" : orderNumber.strip().toUpperCase(Locale.ROOT);
        return withReturns(orders.findByOrderNumber(number)
                .filter(order -> order.getCustomerId().equals(customerId))
                .orElseThrow(OrderException::orderNotFound));
    }

    /** {@code GET /api/orders?status=}: one status, {@code OPEN} (CREATED + AWAITING_PAYMENT) or all. */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> customerOrders(UUID customerId, String status, Integer page, Integer size) {
        return Pages.toResponse(orders.findAll(
                OrderSpecifications.matching(customerId, statuses(status), null, null, null, null, null),
                Pages.request(page, size, NEWEST_FIRST)), OrderMapper::summary);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderSummary> adminOrders(AdminFilter filter, Integer page, Integer size) {
        return Pages.toResponse(orders.findAll(adminFilter(filter), Pages.request(page, size, NEWEST_FIRST)),
                OrderMapper::adminSummary);
    }

    /**
     * {@code GET /api/orders/export}: the customer's own orders with the list's filter, every page, in keyset
     * batches (no transaction here: each batch has its own).
     */
    public RowSource<OrderSummary> customerOrdersExport(UUID customerId, String status) {
        return exports.specification(orders,
                        OrderSpecifications.matching(customerId, statuses(status), null, null, null, null, null))
                .orderBy(OrderExports.ORDER_SORT.resolve(null))
                .rows(OrderMapper::summary);
    }

    /** {@code GET /api/admin/orders/export}: the admin list's filters, every page; {@code sort} from the client. */
    public RowSource<AdminOrderSummary> adminOrdersExport(AdminFilter filter, String sort) {
        return exports.specification(orders, adminFilter(filter))
                .orderBy(OrderExports.ORDER_SORT.resolve(sort))
                .rows(OrderMapper::adminSummary);
    }

    /** The admin list's filters (shared by the list and its export). */
    private Specification<CustomerOrder> adminFilter(AdminFilter filter) {
        ZoneId zone = properties.getTimezone();
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BadRequestException("INVALID_PARAMETER", "'from' must not be after 'to'");
        }
        Instant createdFrom = filter.from() == null ? null : filter.from().atStartOfDay(zone).toInstant();
        Instant createdUntil = filter.to() == null ? null : filter.to().plusDays(1).atStartOfDay(zone).toInstant();
        String orderNumber = filter.q() == null || filter.q().isBlank()
                ? null
                : filter.q().strip().toUpperCase(Locale.ROOT);
        return OrderSpecifications.matching(filter.customerId(), statuses(filter.status()),
                BadRequestException.parseEnum(OrderType.class, "type", filter.type()), filter.storeId(),
                orderNumber, createdFrom, createdUntil);
    }

    @Transactional(readOnly = true)
    public AdminOrderView adminOrder(UUID orderId) {
        CustomerOrder order = orders.findById(orderId).orElseThrow(OrderException::orderNotFound);
        return OrderMapper.adminView(order, returns.ofOrder(order));
    }

    /** Phase 12: the order view with its returns and whether one can be requested now. */
    private OrderView withReturns(CustomerOrder order) {
        return OrderMapper.view(order, returns.ofOrder(order));
    }

    /**
     * One day in the business time zone ({@code grocery.order.timezone}, default today): the orders created that
     * day by their current status and type, and the totals of the orders confirmed (paid) that day.
     */
    @Transactional(readOnly = true)
    public OrderStats stats(LocalDate date) {
        ZoneId zone = properties.getTimezone();
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(zone));
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();

        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            byStatus.put(status, 0L);
        }
        Map<OrderType, Long> byType = new EnumMap<>(OrderType.class);
        for (OrderType type : OrderType.values()) {
            byType.put(type, 0L);
        }
        long created = 0;
        for (Object[] row : orders.countCreatedBetween(from, to)) {
            long count = ((Number) row[2]).longValue();
            byStatus.merge((OrderStatus) row[0], count, Long::sum);
            byType.merge((OrderType) row[1], count, Long::sum);
            created += count;
        }

        List<Object[]> confirmedRows = orders.confirmedBetween(from, to);
        Object[] confirmed = confirmedRows.isEmpty() ? new Object[]{0L, null} : confirmedRows.getFirst();
        long confirmedCount = confirmed[0] == null ? 0 : ((Number) confirmed[0]).longValue();
        BigDecimal revenue = confirmed[1] == null
                ? ZERO
                : new BigDecimal(confirmed[1].toString()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal average = confirmedCount == 0
                ? ZERO
                : revenue.divide(BigDecimal.valueOf(confirmedCount), 2, RoundingMode.HALF_UP);
        return new OrderStats(day, zone.getId(), created, byStatus, byType, revenue, average,
                CustomerOrder.CURRENCY);
    }

    /**
     * {@code GET /api/admin/orders/stuck} (Phase 10 §4): every order the watchdog flagged that is still in the flagged
     * status, longest overdue first; {@code minutesOverdue} = whole minutes past its threshold (never negative).
     */
    @Transactional(readOnly = true)
    public List<StuckOrderView> stuckOrders() {
        Instant now = clock.instant();
        return stuckOrders.open().stream()
                .map(flag -> new StuckOrderView(flag.orderId(), flag.orderNumber(), flag.type(), flag.status(),
                        flag.reason(), flag.since(), flag.detectedAt(),
                        Math.max(0, Duration.between(flag.dueAt(), now).toMinutes()),
                        new StuckStoreView(flag.storeId(), flag.storeName()), flag.customerId()))
                .toList();
    }

    /** {@code GET /api/admin/orders/{id}/events} (Phase 10 §5): the order's event timeline, oldest first; 404 if unknown. */
    @Transactional(readOnly = true)
    public List<OrderEventView> orderEvents(UUID orderId) {
        if (!orders.existsById(orderId)) {
            throw OrderException.orderNotFound();
        }
        return eventLog.list(orderId).stream()
                .map(entry -> new OrderEventView(entry.eventId(), entry.eventType(), entry.sourceTopic(),
                        entry.direction(), entry.outcome(), entry.detail(), entry.occurredAt(), entry.recordedAt(),
                        entry.correlationId()))
                .toList();
    }

    /** {@code status} query parameter: blank = all, {@code OPEN}, or one status; anything else 400. */
    static Set<OrderStatus> statuses(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (OPEN.equals(status.trim())) {
            return OrderStatus.OPEN;
        }
        return Set.of(BadRequestException.parseEnum(OrderStatus.class, "status", status));
    }
}
