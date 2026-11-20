package com.grocery.restaurant.repository;

import com.grocery.restaurant.entity.RestaurantStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RestaurantStatusChangeRepository extends JpaRepository<RestaurantStatusChange, UUID> {

    List<RestaurantStatusChange> findByRestaurantIdOrderByChangedAtDesc(UUID restaurantId);
}
