package com.marketflow.identity.persistence;

import com.marketflow.identity.domain.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** Ownership is part of the lookup: another user's address ID simply does not exist for the caller. */
    Optional<Address> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("update Address a set a.isDefault = false where a.userId = :userId and a.isDefault = true")
    void clearDefault(@Param("userId") UUID userId);
}
