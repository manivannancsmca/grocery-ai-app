package com.grocery.order.service;

import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.repository.CustomerOrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Takes the order row lock. <b>The single entry point for changing an existing order.</b>
 *
 * <h2>Locking rule (every status change, every payment-projection change)</h2>
 * Inventory and payment events, the customer's cancel and the admin's cancel can reach the same order at the same
 * moment (e.g. a cancel racing a {@code PaymentCaptured}). Each of them does this, in one transaction:
 * <ol>
 *   <li>(event consumers) insert the {@code processed_event} marker — a duplicate stops here;</li>
 *   <li><b>lock first:</b> {@code SELECT … FOR UPDATE} on the {@code customer_order} row ({@link #lock}) before
 *       reading anything else of the order. InnoDB creates the transaction's consistent-read snapshot at its first
 *       non-locking read, so the lines, history and rejection rows read afterwards include every change the previous
 *       lock holder committed;</li>
 *   <li>check the state guard on the locked row (the contract's table; anything else is ignored and logged, or
 *       409 {@code ORDER_NOT_CANCELLABLE} for an API call) and apply the transition on the entity;</li>
 *   <li>write the history entry (part of the transition) and the outbox event(s) in the same transaction; commit
 *       releases the lock.</li>
 * </ol>
 * Two changes of one order are therefore applied one after the other, each seeing the other's result: of a cancel and
 * a capture, whichever locks second sees the first one's status (a capture after a cancel only marks the payment
 * {@code REFUND_PENDING}). {@code @Version} on the order is a second line of defence. Lock waits that time out and
 * deadlock victims become 409 {@code CONCURRENT_MODIFICATION} (grocery-common) or a Kafka retry.
 *
 * <p>Creating an order needs no lock: the row is new, and the {@code Idempotency-Key} claim (primary key of
 * {@code idempotency_key}) serialises two requests with the same key.
 */
@Component
public class OrderLocks {

    private final CustomerOrderRepository orders;

    public OrderLocks(CustomerOrderRepository orders) {
        this.orders = orders;
    }

    /** {@code SELECT … FOR UPDATE} on one order; null if it does not exist. */
    public CustomerOrder lock(UUID orderId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An order can only be locked inside the changing transaction");
        }
        return orders.lockById(orderId).orElse(null);
    }
}
