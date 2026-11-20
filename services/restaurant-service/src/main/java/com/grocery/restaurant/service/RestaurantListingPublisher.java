package com.grocery.restaurant.service;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.restaurant.entity.MenuCategory;
import com.grocery.restaurant.entity.MenuItem;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.event.RestaurantEvents;
import com.grocery.restaurant.event.RestaurantListingUpdated;
import com.grocery.restaurant.mapper.MenuMapper;
import com.grocery.restaurant.repository.MenuCategoryRepository;
import com.grocery.restaurant.repository.MenuItemRepository;
import com.grocery.restaurant.repository.RestaurantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Publishes {@code RestaurantListingUpdated} — the complete public listing (profile, hours, accepting-orders
 * switch and the whole menu) — to {@code restaurant.events} through the outbox, for catalog-query's search index.
 *
 * <h2>Locking and versioning rule (every change to a listing follows it)</h2>
 * <ol>
 *   <li><b>Lock first.</b> The transaction's FIRST read is the restaurant row with {@code SELECT … FOR UPDATE}
 *       ({@link #lockById} / {@link #lockByOwner}), before any menu row is read. All changes to one
 *       restaurant's listing (menu edits, availability, accepting-orders, approve/suspend/reinstate) are therefore
 *       serialised. Because the lock comes first, the InnoDB read view of the transaction starts after the
 *       previous change committed, so every later plain read sees that change.</li>
 *   <li><b>Change.</b> Make the change (and its checks: limits, uniqueness) while holding the lock.</li>
 *   <li><b>Publish once.</b> Call {@link #publishChange} once, last. If the restaurant was ever approved
 *       ({@code approvedAt != null}) it increments {@code listing_version} and writes ONE outbox event built from
 *       the current state, in the same transaction. Draft/pending/rejected restaurants publish nothing; their
 *       menu goes live with the approval, which publishes the first listing.</li>
 * </ol>
 * Result: a higher {@code listingVersion} never carries older data. catalog-query uses it as the Elasticsearch
 * external version and drops any event whose version is not newer (replays, duplicates, republish).
 *
 * <p>{@link #republishAll} re-sends every ever-approved restaurant's current listing WITHOUT incrementing the
 * version (search ignores equal versions but recreates lost documents). Each batch reads the restaurants first and
 * their menus second inside one transaction snapshot and takes no locks, so a re-sent version is never paired with
 * data older than that version.
 */
@Component
public class RestaurantListingPublisher {

    private static final Logger log = LoggerFactory.getLogger(RestaurantListingPublisher.class);
    /** Smallest UUID in BINARY(16) order: the keyset start. Ids are UUIDv7 and never all zero. */
    private static final UUID FIRST = new UUID(0L, 0L);

    private final RestaurantRepository restaurants;
    private final MenuCategoryRepository categories;
    private final MenuItemRepository items;
    private final OutboxPublisher outbox;
    private final TransactionTemplate batchTransaction;
    private final Clock clock;
    private final int republishBatchSize;

    public RestaurantListingPublisher(RestaurantRepository restaurants, MenuCategoryRepository categories,
                                      MenuItemRepository items, OutboxPublisher outbox,
                                      PlatformTransactionManager transactionManager, Clock clock,
                                      @Value("${restaurant.listings.republish-batch-size:100}") int republishBatchSize) {
        this.restaurants = restaurants;
        this.categories = categories;
        this.items = items;
        this.outbox = outbox;
        this.batchTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.republishBatchSize = republishBatchSize;
    }

    /** Step 1 by restaurant id (admin actions). 404 RESTAURANT_NOT_FOUND. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Restaurant lockById(UUID restaurantId) {
        return markLocked(restaurants.findByIdForUpdate(restaurantId)
                .orElseThrow(RestaurantOnboardingService::notFound));
    }

    /** Step 1 by owner (the signed-in owner's own restaurant). 404 RESTAURANT_NOT_FOUND. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Restaurant lockByOwner(UUID ownerUserId) {
        return markLocked(restaurants.findByOwnerUserIdForUpdate(ownerUserId)
                .orElseThrow(RestaurantOnboardingService::notFound));
    }

    /**
     * Step 3: after the change, for a restaurant locked by this transaction. No-op until the restaurant was
     * approved for the first time.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishChange(Restaurant restaurant) {
        if (!restaurant.isLockedForUpdate()) {
            throw new IllegalStateException("Restaurant " + restaurant.getId()
                    + " must be loaded with lockById/lockByOwner before its listing changes");
        }
        if (!restaurant.isListed()) {
            return;
        }
        restaurant.advanceListingVersion(clock.instant());
        // JPQL queries flush the pending menu changes first, so the snapshot includes this transaction's change.
        publish(MenuMapper.toListing(restaurant, categories.findByRestaurantId(restaurant.getId()),
                items.findByRestaurantId(restaurant.getId())));
    }

    /**
     * Re-sends the current listing of every ever-approved restaurant with its current version, in batches of
     * {@code restaurant.listings.republish-batch-size} (one transaction per batch).
     *
     * @return number of listings re-sent
     */
    public int republishAll() {
        int total = 0;
        UUID after = FIRST;
        while (true) {
            UUID from = after;
            List<UUID> batch = Objects.requireNonNull(batchTransaction.execute(status -> republishBatch(from)));
            total += batch.size();
            if (batch.size() < republishBatchSize) {
                break;
            }
            after = batch.getLast();
        }
        log.info("Republished {} restaurant listings", total);
        return total;
    }

    /** One snapshot: restaurants first, then their menus (plain reads, no locks). Returns the re-sent ids. */
    private List<UUID> republishBatch(UUID after) {
        List<Restaurant> batch = restaurants.findListedAfter(after, Limit.of(republishBatchSize));
        if (batch.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = batch.stream().map(Restaurant::getId).toList();
        Map<UUID, List<MenuCategory>> categoriesByRestaurant = categories.findByRestaurantIdIn(ids).stream()
                .collect(Collectors.groupingBy(MenuCategory::getRestaurantId));
        Map<UUID, List<MenuItem>> itemsByRestaurant = items.findByRestaurantIdIn(ids).stream()
                .collect(Collectors.groupingBy(MenuItem::getRestaurantId));
        for (Restaurant restaurant : batch) {
            publish(MenuMapper.toListing(restaurant,
                    categoriesByRestaurant.getOrDefault(restaurant.getId(), List.of()),
                    itemsByRestaurant.getOrDefault(restaurant.getId(), List.of())));
        }
        return ids;
    }

    private void publish(RestaurantListingUpdated listing) {
        outbox.publish(OutboxEvent.of(RestaurantEvents.TOPIC, RestaurantEvents.AGGREGATE,
                listing.restaurantId().toString(), RestaurantEvents.LISTING_UPDATED, listing));
    }

    private static Restaurant markLocked(Restaurant restaurant) {
        restaurant.markLockedForUpdate();
        return restaurant;
    }
}
