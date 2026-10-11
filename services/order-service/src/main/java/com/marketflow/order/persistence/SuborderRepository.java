package com.marketflow.order.persistence;

import com.marketflow.order.domain.Suborder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SuborderRepository extends JpaRepository<Suborder, UUID> {

    @Query(value = "select s from Suborder s join fetch s.order o where s.vendorId = :vendorId "
            + "and (:status is null or s.status = :status) order by o.createdAt desc",
            countQuery = "select count(s) from Suborder s where s.vendorId = :vendorId "
                    + "and (:status is null or s.status = :status)")
    Page<Suborder> findForVendor(@Param("vendorId") UUID vendorId, @Param("status") Suborder.Status status,
                                 Pageable pageable);

    /** Vendor-scoped lookup: a suborder of another vendor simply does not exist for the caller. */
    @Query("select s from Suborder s join fetch s.order where s.id = :id and s.vendorId = :vendorId")
    Optional<Suborder> findOwned(@Param("id") UUID id, @Param("vendorId") UUID vendorId);
}
