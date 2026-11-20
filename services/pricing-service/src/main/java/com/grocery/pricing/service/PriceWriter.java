package com.grocery.pricing.service;

import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.common.web.NotFoundException;
import com.grocery.pricing.cache.CachedPrice;
import com.grocery.pricing.cache.PriceQuoteCache;
import com.grocery.pricing.entity.PriceChangeRequest;
import com.grocery.pricing.entity.PriceHistory;
import com.grocery.pricing.entity.PriceSource;
import com.grocery.pricing.entity.ProductPrice;
import com.grocery.pricing.entity.ProductRef;
import com.grocery.pricing.event.PricingEvents;
import com.grocery.pricing.mapper.PriceMapper;
import com.grocery.pricing.repository.PriceChangeRequestRepository;
import com.grocery.pricing.repository.ProductRefRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Every write of a live price, and the transactions around price and price-change-request changes.
 *
 * <h2>Locking and versioning rule</h2>
 * Consumers (catalog-query's price indexer, the quote cache) keep a price only when its {@code priceVersion} is
 * newer than the one they have. That is only safe if <b>a higher version never carries older data</b>, and if
 * two concurrent decisions on the same product cannot both succeed. Every transaction that changes a live price
 * or a price-change request — vendor PUT, vendor cancel, admin approve / reject, the catalog projection's
 * archive auto-cancel — therefore:
 * <ol>
 *   <li><b>first</b> locks the product's {@code product_ref} row with {@code SELECT … FOR UPDATE}
 *       ({@link #lockProduct}), <b>before reading anything else</b>. The row exists before the first price
 *       (it comes from the catalog), so even the first price of a product has something to lock. InnoDB creates
 *       a transaction's consistent-read snapshot at its first non-locking read, so everything read after the
 *       lock includes every change committed by the previous lock holder. Commands addressed by request id
 *       first look up the request's product id <em>outside</em> the transaction (it never changes) —
 *       {@link #inRequestTransaction};</li>
 *   <li>then reads the live price and the pending request, checks the rules, and changes them; a live-price
 *       change increments {@code price_version} on the locked product's row ({@link ProductPrice#change});</li>
 *   <li>writes the {@code price_history} row and the outbox events in the same transaction, and registers the
 *       quote-cache update to run after the commit (compare-and-set by version, see {@link PriceQuoteCache}).</li>
 * </ol>
 * Two writers of the same product are thus serialised: the one that commits later reads the newer state and
 * produces the higher version, and of two admins deciding the same request the second one finds it no longer
 * PENDING (409 {@code PRICE_CHANGE_NOT_PENDING}). The unique {@code pending_key} column and the CHECK constraint
 * on {@code product_price} are the database's last line of defence. A lock wait timeout or deadlock becomes 409
 * {@code CONCURRENT_MODIFICATION} (grocery-common) or a Kafka retry.
 *
 * <p><b>Republish</b> does NOT increment versions: it re-sends the current prices with their current version
 * ({@link #republish}); consumers ignore what they already have and rebuild what they lost.
 */
@Component
public class PriceWriter {

    private final ProductRefRepository products;
    private final PriceChangeRequestRepository requests;
    private final OutboxPublisher outbox;
    private final PriceQuoteCache cache;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PriceWriter(ProductRefRepository products, PriceChangeRequestRepository requests, OutboxPublisher outbox,
                       PriceQuoteCache cache, EntityManager entityManager,
                       PlatformTransactionManager transactionManager, Clock clock) {
        this.products = products;
        this.requests = requests;
        this.outbox = outbox;
        this.cache = cache;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** {@code SELECT … FOR UPDATE} on {@code product_ref}: the first statement of every price transaction. */
    public Optional<ProductRef> lockProduct(UUID productId) {
        return products.lockById(productId);
    }

    /** A new transaction whose first statement locks the product ({@code null} when it is unknown). */
    public <T> T inProductTransaction(UUID productId, Function<ProductRef, T> work) {
        return transaction.execute(status -> work.apply(lockProduct(productId).orElse(null)));
    }

    /**
     * A new transaction for a command on a price-change request: the request's product (immutable) is looked up
     * before the transaction; inside it the product is locked first, then the request ({@code FOR UPDATE}).
     * {@code vendorId} != null restricts to that vendor's requests. Unknown → 404 PRICE_CHANGE_REQUEST_NOT_FOUND.
     */
    public <T> T inRequestTransaction(UUID requestId, UUID vendorId,
                                      BiFunction<ProductRef, PriceChangeRequest, T> work) {
        List<Object[]> owner = requests.findOwnerById(requestId);
        if (owner.isEmpty() || (vendorId != null && !vendorId.equals(owner.getFirst()[1]))) {
            throw requestNotFound();
        }
        UUID productId = (UUID) owner.getFirst()[0];
        return transaction.execute(status -> {
            ProductRef product = lockProduct(productId).orElseThrow(PriceWriter::requestNotFound);
            PriceChangeRequest request = requests.lockById(requestId).orElseThrow(PriceWriter::requestNotFound);
            return work.apply(product, request);
        });
    }

    /**
     * Sets the live price — the first price ({@code current} null, version 1) or a change (version + 1) — and
     * writes the history entry, {@code PriceChanged} and (after commit) the quote cache. The caller holds the
     * product lock and has checked the rules. Amounts have 2 decimals.
     */
    public ProductPrice apply(ProductRef product, ProductPrice current, BigDecimal mrp, BigDecimal sellingPrice,
                              PriceSource source, UUID requestId, UUID changedBy) {
        Instant now = clock.instant();
        BigDecimal oldMrp = current == null ? null : current.getMrp();
        BigDecimal oldSellingPrice = current == null ? null : current.getSellingPrice();
        BigDecimal changePercent = current == null ? null : PriceRules.changePercent(oldSellingPrice, sellingPrice);

        ProductPrice price = current;
        if (price == null) {
            price = ProductPrice.first(product.getProductId(), product.getVendorId(), mrp, sellingPrice, changedBy, now);
            entityManager.persist(price);
        } else {
            price.change(mrp, sellingPrice, changedBy, now);
        }
        entityManager.persist(PriceHistory.of(Ids.newId(), product.getProductId(), price.getVendorId(), oldMrp,
                oldSellingPrice, mrp, sellingPrice, changePercent, source, requestId, changedBy, now));
        entityManager.flush();

        publish(product.getProductId(), PricingEvents.PRICE_CHANGED, PriceMapper.toPriceChanged(price));
        cache.putAfterCommit(CachedPrice.of(price, product));
        return price;
    }

    /** Re-sends the current price with its CURRENT version (no increment, no history). */
    public void republish(ProductPrice price) {
        publish(price.getProductId(), PricingEvents.PRICE_CHANGED, PriceMapper.toPriceChanged(price));
    }

    /** Writes a {@code pricing.events} event (aggregate {@code Price}, key productId) in the caller's transaction. */
    public void publish(UUID productId, String eventType, Object payload) {
        outbox.publish(OutboxEvent.of(PricingEvents.TOPIC, PricingEvents.AGGREGATE, productId.toString(),
                eventType, payload));
    }

    public static NotFoundException productNotFound() {
        return new NotFoundException("PRODUCT_NOT_FOUND", "Product not found");
    }

    public static NotFoundException requestNotFound() {
        return new NotFoundException("PRICE_CHANGE_REQUEST_NOT_FOUND", "Price-change request not found");
    }
}
