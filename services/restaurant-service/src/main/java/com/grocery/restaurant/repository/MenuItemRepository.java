package com.grocery.restaurant.repository;

import com.grocery.restaurant.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every lookup is scoped by restaurant: another restaurant's id behaves exactly like an unknown id. */
public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

    Optional<MenuItem> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    List<MenuItem> findByRestaurantId(UUID restaurantId);

    List<MenuItem> findByRestaurantIdIn(Collection<UUID> restaurantIds);

    /** The given items that belong to this restaurant (menu quote); other restaurants' ids are not returned. */
    List<MenuItem> findByRestaurantIdAndIdIn(UUID restaurantId, Collection<UUID> ids);

    List<MenuItem> findByCategoryId(UUID categoryId);

    long countByRestaurantId(UUID restaurantId);

    boolean existsByCategoryId(UUID categoryId);
}
