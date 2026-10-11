package com.marketflow.vendor.persistence;

import com.marketflow.vendor.domain.EarningsEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

public interface EarningsRepository extends JpaRepository<EarningsEntry, UUID> {

    Page<EarningsEntry> findByVendorIdOrderByOccurredAtDesc(UUID vendorId, Pageable pageable);

    boolean existsBySuborderIdAndType(UUID suborderId, EarningsEntry.Type type);

    interface Totals {
        BigDecimal getGross();

        BigDecimal getCommission();

        BigDecimal getNet();

        long getSales();

        long getRefunds();
    }

    @Query("""
            select coalesce(sum(e.grossAmount), 0) as gross, coalesce(sum(e.commission), 0) as commission,
                   coalesce(sum(e.netAmount), 0) as net,
                   sum(case when e.type = com.marketflow.vendor.domain.EarningsEntry.Type.SALE then 1 else 0 end) as sales,
                   sum(case when e.type = com.marketflow.vendor.domain.EarningsEntry.Type.REFUND then 1 else 0 end) as refunds
            from EarningsEntry e where e.vendorId = :vendorId
            """)
    Totals totals(@Param("vendorId") UUID vendorId);
}
