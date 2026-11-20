package com.grocery.order.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.order.dto.OrderMapper;
import com.grocery.order.dto.ReturnDtos.ReturnView;
import com.grocery.order.dto.OrderExports;
import com.grocery.order.dto.ReturnMapper;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.OrderReturn;
import com.grocery.order.entity.ReturnStatus;
import com.grocery.order.repository.CustomerOrderRepository;
import com.grocery.order.repository.OrderReturnRepository;
import com.grocery.order.repository.ReturnSpecifications;
import com.grocery.order.settings.ReturnSettingsService;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The return lists (docs/api/phase-12-post-mvp-features.md §3) and the returns part of the order views
 * ({@code returnable}, {@code returns}). Plain consistent reads in read-only transactions; lists are newest first.
 */
@Service
public class ReturnQueryService {

    /** The admin list's default: the returns waiting for a decision. */
    static final Set<ReturnStatus> OPEN = EnumSet.of(ReturnStatus.REQUESTED, ReturnStatus.ESCALATED);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"));

    private final OrderReturnRepository returns;
    private final CustomerOrderRepository orders;
    private final ReturnSettingsService settings;
    private final ExportQueries exports;
    private final Clock clock;

    public ReturnQueryService(OrderReturnRepository returns, CustomerOrderRepository orders,
                              ReturnSettingsService settings, ExportQueries exports, Clock clock) {
        this.returns = returns;
        this.orders = orders;
        this.settings = settings;
        this.exports = exports;
        this.clock = clock;
    }

    /** {@code GET /api/orders/{id}/returns}: the customer's own order (another's → 404 ORDER_NOT_FOUND), oldest first. */
    @Transactional(readOnly = true)
    public List<ReturnView> ofCustomerOrder(UUID customerId, UUID orderId) {
        CustomerOrder order = orders.findById(orderId)
                .filter(o -> o.getCustomerId().equals(customerId))
                .orElseThrow(OrderException::orderNotFound);
        return returns.findByOrderIdOrderByRequestedAtAscIdAsc(order.getId()).stream()
                .map(ReturnMapper::view).toList();
    }

    /** {@code GET /api/returns}: the customer's returns, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ReturnView> ofCustomer(UUID customerId, Integer page, Integer size) {
        return Pages.toResponse(returns.findAll(ReturnSpecifications.matching(customerId, null, null, null),
                Pages.request(page, size, NEWEST_FIRST)), ReturnMapper::view);
    }

    /** {@code GET /api/store/returns?status=}: the caller's store's returns (one status or all), newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ReturnView> ofStore(StoreRef store, String status, Integer page, Integer size) {
        return Pages.toResponse(returns.findAll(storeFilter(store, status), Pages.request(page, size, NEWEST_FIRST)),
                ReturnMapper::view);
    }

    /** {@code GET /api/admin/returns?status=&storeId=}: default REQUESTED + ESCALATED, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ReturnView> forAdmins(String status, UUID storeId, Integer page, Integer size) {
        return Pages.toResponse(returns.findAll(adminFilter(status, storeId), Pages.request(page, size, NEWEST_FIRST)),
                ReturnMapper::view);
    }

    /** {@code GET /api/returns/export}: the customer's returns, every page, in keyset batches. */
    public RowSource<ReturnView> ofCustomerExport(UUID customerId, String sort) {
        return export(ReturnSpecifications.matching(customerId, null, null, null), sort);
    }

    /** {@code GET /api/store/returns/export}: the caller's store's returns with the list's status filter. */
    public RowSource<ReturnView> ofStoreExport(StoreRef store, String status, String sort) {
        return export(storeFilter(store, status), sort);
    }

    /** {@code GET /api/admin/returns/export}: the admin list's filters (default: waiting for a decision). */
    public RowSource<ReturnView> forAdminsExport(String status, UUID storeId, String sort) {
        return export(adminFilter(status, storeId), sort);
    }

    private RowSource<ReturnView> export(Specification<OrderReturn> filter, String sort) {
        return exports.specification(returns, filter)
                .orderBy(OrderExports.RETURN_SORT.resolve(sort))
                .rows(ReturnMapper::view);
    }

    private static Specification<OrderReturn> storeFilter(StoreRef store, String status) {
        Set<ReturnStatus> statuses = status == null || status.isBlank()
                ? null
                : Set.of(BadRequestException.parseEnum(ReturnStatus.class, "status", status));
        return ReturnSpecifications.matching(null, store.type(), store.storeId(), statuses);
    }

    private static Specification<OrderReturn> adminFilter(String status, UUID storeId) {
        Set<ReturnStatus> statuses = status == null || status.isBlank()
                ? OPEN
                : Set.of(BadRequestException.parseEnum(ReturnStatus.class, "status", status));
        return ReturnSpecifications.matching(null, null, storeId, statuses);
    }

    /**
     * The returns part of {@code order}'s view, in the caller's transaction: its requests (oldest first) and whether
     * the customer may request one now ({@link ReturnRules#refusal} at the clock's time).
     */
    public OrderMapper.Returns ofOrder(CustomerOrder order) {
        List<OrderReturn> requests = returns.findByOrderIdOrderByRequestedAtAscIdAsc(order.getId());
        boolean returnable = ReturnRules.refusal(order, requests, clock.instant(), settings.current().returnWindow())
                .isEmpty();
        return new OrderMapper.Returns(returnable, requests);
    }
}
