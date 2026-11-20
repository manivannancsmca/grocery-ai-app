package com.grocery.order.dto;

import com.grocery.order.entity.DeciderRole;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.ReturnReason;
import com.grocery.order.entity.ReturnStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Return API bodies (docs/api/phase-12-post-mvp-features.md §3). Money is a JSON number with 2 decimals (INR); nulls
 * are written out ({@code "restock": null} until a decision).
 */
public final class ReturnDtos {

    public static final int MAX_LINES = 100;
    public static final int MAX_QUANTITY = 1000;

    private ReturnDtos() {
    }

    // ---- requests ---------------------------------------------------------------------------------------------

    /** One line to return: an order line's {@code itemId} and how many. */
    public record ReturnLineRequest(@NotNull UUID itemId, @NotNull @Min(1) @Max(MAX_QUANTITY) Integer quantity) {
    }

    /** POST /api/orders/{id}/returns. The same item twice is added up. */
    public record ReturnRequest(@NotEmpty @Size(max = MAX_LINES) List<@NotNull @Valid ReturnLineRequest> items,
                                @NotNull ReturnReason reason,
                                @Size(max = 500) String note) {
    }

    /** POST …/returns/{id}/approve — {@code restock}: the goods came back sellable (forced false for RESTAURANT). */
    public record ApproveReturnRequest(Boolean restock) {
    }

    /** POST …/returns/{id}/reject — the comment is shown to the customer. */
    public record RejectReturnRequest(@NotBlank @Size(max = 300) String comment) {
    }

    // ---- views ------------------------------------------------------------------------------------------------

    public record ReturnItemView(UUID itemId, String name, int quantity, BigDecimal refundAmount) {
    }

    /**
     * A return request. {@code refundAmount} = the lines' refunds plus, on a return of every remaining unit, the paid
     * delivery fee; {@code restock} null until approved; {@code decideBy} = when it escalates to admins if the store
     * has not decided; {@code decidedByRole} STORE | ADMIN.
     */
    public record ReturnView(UUID id, String returnNumber, UUID orderId, String orderNumber, OrderType orderType,
                             UUID storeId, String storeName, ReturnStatus status, ReturnReason reason, String note,
                             List<ReturnItemView> items, BigDecimal refundAmount, Boolean restock, Instant decideBy,
                             Instant requestedAt, Instant decidedAt, DeciderRole decidedByRole, String comment,
                             Instant refundedAt) {
    }

    /** A returned line as {@code OrderView.returns[].items} lists it (the client shows what is still returnable). */
    public record ReturnSummaryItem(UUID itemId, int quantity) {
    }

    /** A return as {@code OrderView.returns} lists it, oldest first. */
    public record ReturnSummary(UUID id, String returnNumber, ReturnStatus status, ReturnReason reason,
                                BigDecimal refundAmount, Instant requestedAt, List<ReturnSummaryItem> items) {
    }
}
