package com.marketflow.vendor.persistence;

import com.marketflow.vendor.domain.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    List<Payout> findByVendorIdOrderByCreatedAtDesc(UUID vendorId);

    @Query("select coalesce(sum(p.amount), 0) from Payout p where p.vendorId = :vendorId")
    BigDecimal totalPaid(@Param("vendorId") UUID vendorId);
}
