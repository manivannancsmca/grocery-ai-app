package com.grocery.restaurant.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.restaurant.dto.MenuDtos.AcceptingOrdersView;
import com.grocery.restaurant.entity.Restaurant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Day-to-day switches of the signed-in owner's restaurant. Restaurant order handling (accept / reject /
 * preparing / ready) joins this service in Phase 8.
 */
@Service
public class RestaurantOperationsService {

    private final RestaurantListingPublisher listings;
    private final Clock clock;

    public RestaurantOperationsService(RestaurantListingPublisher listings, Clock clock) {
        this.listings = listings;
        this.clock = clock;
    }

    /**
     * Temporarily stop (or resume) new orders; the restaurant stays visible in search with
     * {@code acceptingOrders=false}. Setting the current value again changes and publishes nothing.
     */
    @Transactional
    public AcceptingOrdersView setAcceptingOrders(CurrentUser owner, boolean acceptingOrders) {
        Restaurant restaurant = listings.lockByOwner(owner.userId());
        if (restaurant.changeAcceptingOrders(acceptingOrders, clock.instant())) {
            listings.publishChange(restaurant);
        }
        return new AcceptingOrdersView(restaurant.isAcceptingOrders());
    }
}
