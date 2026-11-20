package com.grocery.restaurant.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.restaurant.dto.RestaurantDtos.RestaurantView;
import com.grocery.restaurant.dto.RestaurantDtos.StatusChangeView;
import com.grocery.restaurant.dto.RestaurantExports;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.entity.RestaurantStatus;
import com.grocery.restaurant.event.RestaurantEvents;
import com.grocery.restaurant.mapper.RestaurantMapper;
import com.grocery.restaurant.repository.RestaurantRepository;
import com.grocery.restaurant.repository.RestaurantStatusChangeRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Admin side of the lifecycle: review queue, approve/reject, suspend/reinstate, history.
 * Status changes lock the restaurant row first; approve, suspend and reinstate then also publish the public
 * listing (after the unchanged lifecycle event), following {@link RestaurantListingPublisher}'s rule.
 */
@Service
public class RestaurantAdminService {

    private final RestaurantRepository restaurants;
    private final RestaurantStatusChangeRepository history;
    private final RestaurantLifecycleRecorder recorder;
    private final RestaurantListingPublisher listings;
    private final ExportQueries exports;
    private final Clock clock;

    public RestaurantAdminService(RestaurantRepository restaurants, RestaurantStatusChangeRepository history,
                                  RestaurantLifecycleRecorder recorder, RestaurantListingPublisher listings,
                                  ExportQueries exports, Clock clock) {
        this.restaurants = restaurants;
        this.history = history;
        this.recorder = recorder;
        this.listings = listings;
        this.exports = exports;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<RestaurantView> search(String status, String query, Integer page, Integer size) {
        RestaurantStatus statusFilter = BadRequestException.parseEnum(RestaurantStatus.class, "status", status);
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        // Review queue: oldest submission first. Everything else: most recently changed first.
        Sort sort = statusFilter == RestaurantStatus.PENDING_APPROVAL
                ? Sort.by(Sort.Direction.ASC, "submittedAt")
                : Sort.by(Sort.Direction.DESC, "updatedAt");
        return Pages.toResponse(restaurants.search(statusFilter, like, Pages.request(page, size, sort)),
                RestaurantMapper::toView);
    }

    /**
     * {@code GET /api/admin/restaurants/export}: the search's filters on every page, in the list's order (the review
     * queue oldest submission first, else most recently changed first), in keyset batches. No transaction here: each
     * batch has its own.
     */
    public RowSource<RestaurantView> searchExport(String status, String query) {
        RestaurantStatus statusFilter = BadRequestException.parseEnum(RestaurantStatus.class, "status", status);
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return exports.jpql(Restaurant.class, "r", RestaurantRepository.ADMIN_SEARCH)
                .param("status", statusFilter)
                .param("query", like)
                .orderBy(statusFilter == RestaurantStatus.PENDING_APPROVAL
                        ? RestaurantExports.REVIEW_QUEUE : RestaurantExports.RECENTLY_CHANGED)
                .rows(RestaurantMapper::toView);
    }

    @Transactional(readOnly = true)
    public RestaurantView get(UUID restaurantId) {
        return RestaurantMapper.toView(load(restaurantId));
    }

    @Transactional(readOnly = true)
    public List<StatusChangeView> history(UUID restaurantId) {
        load(restaurantId);
        return history.findByRestaurantIdOrderByChangedAtDesc(restaurantId).stream()
                .map(RestaurantMapper::toView).toList();
    }

    /** The first approval publishes the first listing (version 1), menu included. */
    @Transactional
    public RestaurantView approve(UUID restaurantId, CurrentUser admin) {
        Restaurant restaurant = listings.lockById(restaurantId);
        RestaurantStatus from = restaurant.approve(clock.instant());
        recorder.record(restaurant, from, RestaurantEvents.APPROVED, null, admin.userId());
        listings.publishChange(restaurant);
        return RestaurantMapper.toView(restaurant);
    }

    /** Never approved yet, so no listing to publish. */
    @Transactional
    public RestaurantView reject(UUID restaurantId, String reason, CurrentUser admin) {
        Restaurant restaurant = listings.lockById(restaurantId);
        RestaurantStatus from = restaurant.reject(reason, clock.instant());
        recorder.record(restaurant, from, RestaurantEvents.REJECTED, reason, admin.userId());
        return RestaurantMapper.toView(restaurant);
    }

    /** The listing is re-published with status SUSPENDED (search hides it). */
    @Transactional
    public RestaurantView suspend(UUID restaurantId, String reason, CurrentUser admin) {
        Restaurant restaurant = listings.lockById(restaurantId);
        RestaurantStatus from = restaurant.suspend(reason, clock.instant());
        recorder.record(restaurant, from, RestaurantEvents.SUSPENDED, reason, admin.userId());
        listings.publishChange(restaurant);
        return RestaurantMapper.toView(restaurant);
    }

    @Transactional
    public RestaurantView reinstate(UUID restaurantId, CurrentUser admin) {
        Restaurant restaurant = listings.lockById(restaurantId);
        RestaurantStatus from = restaurant.reinstate(clock.instant());
        recorder.record(restaurant, from, RestaurantEvents.REINSTATED, null, admin.userId());
        listings.publishChange(restaurant);
        return RestaurantMapper.toView(restaurant);
    }

    /** Re-sends every ever-approved restaurant's listing without changing versions. */
    public int republishListings() {
        return listings.republishAll();
    }

    private Restaurant load(UUID restaurantId) {
        return restaurants.findById(restaurantId).orElseThrow(RestaurantOnboardingService::notFound);
    }
}
