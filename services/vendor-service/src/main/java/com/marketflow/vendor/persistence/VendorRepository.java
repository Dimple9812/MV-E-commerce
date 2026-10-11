package com.marketflow.vendor.persistence;

import com.marketflow.vendor.domain.Vendor;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByOwnerUserId(UUID ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vendor v where v.id = :id")
    Optional<Vendor> findForUpdate(@Param("id") UUID id);

    boolean existsBySlug(String slug);

    Page<Vendor> findByStatus(Vendor.Status status, Pageable pageable);
}
