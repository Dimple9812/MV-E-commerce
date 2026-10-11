package com.marketflow.vendor.persistence;

import com.marketflow.vendor.domain.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, Long> {
    List<StatusHistory> findByVendorIdOrderByOccurredAtAsc(UUID vendorId);
}
