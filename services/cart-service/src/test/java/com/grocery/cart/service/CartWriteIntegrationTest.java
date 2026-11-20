package com.grocery.cart.service;

import com.grocery.cart.AbstractIntegrationTest;
import com.grocery.cart.model.ItemType;
import com.grocery.cart.model.Store;
import com.grocery.cart.model.StoreType;
import com.grocery.cart.model.StoredCart;
import com.grocery.cart.model.StoredLine;
import com.grocery.cart.store.CartStore;
import com.grocery.cart.store.CartStore.VersionedCart;
import com.grocery.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The compare-and-set write path, with another writer forced between a read and its write. */
class CartWriteIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CartStore store;
    @Autowired
    CartService carts;

    private final Store greenBasket = new Store(UUID.randomUUID(), StoreType.GROCERY, "Green Basket");
    private final Instant now = Instant.parse("2026-11-11T10:15:30Z");

    private StoredLine line(int quantity) {
        return new StoredLine(UUID.randomUUID(), ItemType.PRODUCT, quantity, new BigDecimal("90.00"), "Rice", "1 kg",
                null, "VEG", now);
    }

    @Test
    void compareAndSetRejectsAStaleVersion() {
        UUID customerId = UUID.randomUUID();
        VersionedCart nothing = store.load(customerId);
        assertThat(nothing.version()).isZero();
        assertThat(nothing.cart().isEmpty()).isTrue();

        StoredCart first = StoredCart.EMPTY.withLine(greenBasket, line(1), now);
        StoredCart second = StoredCart.EMPTY.withLine(greenBasket, line(2), now);
        assertThat(store.compareAndSet(customerId, 0, first)).isTrue();
        assertThat(store.compareAndSet(customerId, 0, second)).isFalse();      // read before the first write

        VersionedCart stored = store.load(customerId);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.cart()).isEqualTo(first);
        assertThat(store.compareAndSet(customerId, 1, second)).isTrue();
        assertThat(store.load(customerId).version()).isEqualTo(2);
    }

    @Test
    void aWriteOvertakenByAnotherIsRetriedOnTheNewCart() {
        UUID customerId = UUID.randomUUID();
        StoredLine otherTab = line(3);
        StoredLine mine = line(1);
        AtomicInteger attempts = new AtomicInteger();

        StoredCart result = carts.write(customerId, cart -> {
            if (attempts.incrementAndGet() == 1) {          // another tab writes between our read and our write
                VersionedCart current = store.load(customerId);
                store.compareAndSet(customerId, current.version(),
                        current.cart().withLine(greenBasket, otherTab, now));
            }
            return cart.withLine(greenBasket, mine, now);
        });

        assertThat(attempts).hasValue(2);
        VersionedCart stored = store.load(customerId);
        assertThat(stored.version()).isEqualTo(2);
        assertThat(stored.cart()).isEqualTo(result);
        assertThat(stored.cart().itemIds()).containsExactly(otherTab.itemId(), mine.itemId());
    }

    @Test
    void givesUpWithConcurrentModificationAfterThreeRetries() {
        UUID customerId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> carts.write(customerId, cart -> {
            attempts.incrementAndGet();
            VersionedCart current = store.load(customerId);      // always overtaken
            store.compareAndSet(customerId, current.version(), current.cart().withLine(greenBasket, line(1), now));
            return cart.withLine(greenBasket, line(1), now);
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo("CONCURRENT_MODIFICATION"));
        assertThat(attempts).hasValue(4);
    }

    @Test
    void nothingIsWrittenWhenNothingChanges() {
        UUID customerId = UUID.randomUUID();
        carts.write(customerId, cart -> cart.without(UUID.randomUUID(), now));
        carts.write(customerId, cart -> cart.cleared(now));
        assertThat(redis.hasKey(CartStore.key(customerId))).isFalse();
    }
}
