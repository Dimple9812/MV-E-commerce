package com.marketflow.catalog.persistence;

import com.marketflow.catalog.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findAllByOrderBySortOrderAscNameAsc();

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByParentId(UUID parentId);

    @Query(value = "SELECT count(*) > 0 FROM product WHERE category_id = :id", nativeQuery = true)
    boolean hasProducts(@Param("id") UUID id);
}
