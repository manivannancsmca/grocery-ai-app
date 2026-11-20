package com.grocery.restaurant.repository;

import com.grocery.restaurant.entity.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every lookup is scoped by restaurant: another restaurant's id behaves exactly like an unknown id. */
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, UUID> {

    Optional<MenuCategory> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    List<MenuCategory> findByRestaurantId(UUID restaurantId);

    List<MenuCategory> findByRestaurantIdIn(Collection<UUID> restaurantIds);

    long countByRestaurantId(UUID restaurantId);

    boolean existsByRestaurantIdAndNameIgnoreCase(UUID restaurantId, String name);

    boolean existsByRestaurantIdAndNameIgnoreCaseAndIdNot(UUID restaurantId, String name, UUID id);
}
