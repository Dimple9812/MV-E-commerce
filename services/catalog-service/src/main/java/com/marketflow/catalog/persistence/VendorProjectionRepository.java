package com.marketflow.catalog.persistence;

import com.marketflow.catalog.domain.VendorProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VendorProjectionRepository extends JpaRepository<VendorProjection, UUID> {

    Optional<VendorProjection> findByOwnerUserId(UUID ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VendorProjection v where v.vendorId = :id")
    Optional<VendorProjection> findForUpdate(@Param("id") UUID id);
}
