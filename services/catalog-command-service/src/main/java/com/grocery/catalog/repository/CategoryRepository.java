package com.grocery.catalog.repository;

import com.grocery.catalog.entity.Category;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /**
     * {@code SELECT … FOR UPDATE} on every category. Category commands lock the whole (small) tree first:
     * paths, depths, cycles and sibling names all depend on other nodes, and admin edits are rare.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c order by c.id")
    List<Category> lockAll();

    /**
     * {@code SELECT … FOR SHARE} on one category. Product commands take it so a concurrent category
     * rename/move/(de)activation (which holds FOR UPDATE on the whole tree) cannot interleave with them.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Category c where c.id = :id")
    Optional<Category> lockForShare(@Param("id") UUID id);
}
