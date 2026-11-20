package com.grocery.order.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.idempotency.IdempotencyKeys;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.order.client.CallContext;
import com.grocery.order.dto.OrderDtos.CancelRequest;
import com.grocery.order.dto.OrderExports;
import com.grocery.order.dto.OrderDtos.OrderSummary;
import com.grocery.order.dto.OrderDtos.OrderView;
import com.grocery.order.dto.OrderDtos.PlaceOrderRequest;
import com.grocery.order.dto.ReturnDtos.ReturnRequest;
import com.grocery.order.dto.ReturnDtos.ReturnView;
import com.grocery.order.service.OrderCancellationService;
import com.grocery.order.service.OrderPlacementService;
import com.grocery.order.service.OrderPlacementService.Placed;
import com.grocery.order.service.OrderQueryService;
import com.grocery.order.service.ReturnQueryService;
import com.grocery.order.service.ReturnService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * The customer's own orders (contract §2) and, Phase 12 §3, their returns. customerId = the JWT subject; another
 * customer's order is 404.
 */
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class OrderController {

    /** Set on the 201 answer to a retried {@code POST /api/orders} (same key, same body): nothing was created. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OrderPlacementService placement;
    private final OrderQueryService queries;
    private final OrderCancellationService cancellation;
    private final ReturnService returns;
    private final ReturnQueryService returnQueries;
    private final Exporter exporter;

    public OrderController(OrderPlacementService placement, OrderQueryService queries,
                           OrderCancellationService cancellation, ReturnService returns,
                           ReturnQueryService returnQueries, Exporter exporter) {
        this.placement = placement;
        this.queries = queries;
        this.cancellation = cancellation;
        this.returns = returns;
        this.returnQueries = returnQueries;
        this.exporter = exporter;
    }

    /**
     * The key is checked before the body (400 IDEMPOTENCY_KEY_REQUIRED / _INVALID), so both are read leniently here
     * and validated by {@link OrderPlacementService}.
     */
    @PostMapping
    public ResponseEntity<OrderView> place(CurrentUser user,
                                           @RequestHeader(name = IdempotencyKeys.HEADER, required = false)
                                           String idempotencyKey,
                                           @RequestBody(required = false) PlaceOrderRequest request) {
        Placed placed = placement.place(user.userId(), idempotencyKey, request, CallContext.current());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/orders/" + placed.order().id()));
        if (placed.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(placed.order());
    }

    @GetMapping
    public PageResponse<OrderSummary> list(CurrentUser user, @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return queries.customerOrders(user.userId(), status, page, size);
    }

    /** The caller's own orders as a file ({@code format} = csv | xlsx | pdf), with the list's status filter. */
    @GetMapping("/export")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String status, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, OrderExports.MY_ORDERS,
                queries.customerOrdersExport(user.userId(), status), Exporter.filters("Status", status));
    }

    @GetMapping("/{id}")
    public OrderView get(CurrentUser user, @PathVariable UUID id) {
        return queries.customerOrder(user.userId(), id);
    }

    /** The body and its reason are optional; cancelling a cancelled order returns it unchanged. */
    @PostMapping("/{id}/cancel")
    public OrderView cancel(CurrentUser user, @PathVariable UUID id,
                            @Valid @RequestBody(required = false) CancelRequest request) {
        return cancellation.cancelByCustomer(user.userId(), id, request == null ? null : request.reason());
    }

    /**
     * Phase 12 §3: asks to return lines of a delivered order (the AI assistant calls it with an approval token too).
     * 422 RETURN_NOT_ALLOWED / RETURN_QUANTITY_EXCEEDED; another customer's order → 404 ORDER_NOT_FOUND.
     */
    @PostMapping("/{id}/returns")
    public ResponseEntity<ReturnView> requestReturn(CurrentUser user, @PathVariable UUID id,
                                                    @Valid @RequestBody ReturnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(returns.request(user.userId(), id, request));
    }

    /** The order's return requests, oldest first. */
    @GetMapping("/{id}/returns")
    public List<ReturnView> returns(CurrentUser user, @PathVariable UUID id) {
        return returnQueries.ofCustomerOrder(user.userId(), id);
    }
}
