package com.grocery.catalog.repository;

import com.grocery.catalog.entity.Brand;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrandRepository extends JpaRepository<Brand, UUID> {

    Optional<Brand> findByNameKey(String nameKey);

    /** Suggestions: brands whose key starts with {@code fragment} first, then those containing it. */
    @Query("""
            select b from Brand b
            where b.nameKey like concat('%', :fragment, '%')
            order by case when b.nameKey like concat(:fragment, '%') then 0 else 1 end, b.name
            """)
    List<Brand> suggest(@Param("fragment") String fragment, Pageable pageable);

    @Query("select b from Brand b order by b.name")
    List<Brand> firstByName(Pageable pageable);
}
