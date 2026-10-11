package com.marketflow.catalog.persistence;

import com.marketflow.catalog.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    /** Variants are fetch-joined; images load in one batched query (default_batch_fetch_size) - no N+1. */
    @EntityGraph(attributePaths = "variants")
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findDetailed(@Param("id") UUID id);

    /** Ownership is part of the lookup: another vendor's product ID behaves as "not found". */
    @EntityGraph(attributePaths = "variants")
    @Query("select p from Product p where p.id = :id and p.vendorId = :vendorId")
    Optional<Product> findOwned(@Param("id") UUID id, @Param("vendorId") UUID vendorId);

    @Lock(LockModeType.OPTIMISTIC)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForModeration(@Param("id") UUID id);

    Page<Product> findByVendorIdOrderByUpdatedAtDesc(UUID vendorId, Pageable pageable);

    Page<Product> findByVendorIdAndStatusOrderByUpdatedAtDesc(UUID vendorId, Product.Status status, Pageable pageable);

    Page<Product> findByStatusOrderByUpdatedAtDesc(Product.Status status, Pageable pageable);

    boolean existsByVendorIdAndSlug(UUID vendorId, String slug);

    @Query("select p.id from Product p where p.vendorId = :vendorId")
    List<UUID> idsByVendor(@Param("vendorId") UUID vendorId);

    /**
     * Projection updates use bulk SQL so they never collide with a vendor's concurrent edit (no @Version bump)
     * and never trigger product events.
     */
    @Modifying
    @Query(value = """
            UPDATE product_variant SET available_qty = GREATEST(:qty, 0), stock_version = :version
            WHERE id = :variantId AND stock_version < :version
            """, nativeQuery = true)
    int updateVariantStock(@Param("variantId") UUID variantId, @Param("qty") int qty, @Param("version") long version);

    @Modifying
    @Query(value = """
            UPDATE product p SET in_stock = EXISTS (
                SELECT 1 FROM product_variant v WHERE v.product_id = p.id AND v.active AND v.available_qty > 0)
            WHERE p.id = :productId
            """, nativeQuery = true)
    int refreshInStock(@Param("productId") UUID productId);

    @Modifying
    @Query(value = "UPDATE product SET rating_avg = :avg, rating_count = :count WHERE id = :productId",
            nativeQuery = true)
    int updateRating(@Param("productId") UUID productId, @Param("avg") BigDecimal avg, @Param("count") int count);
}
