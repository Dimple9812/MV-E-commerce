package com.marketflow.order.persistence;

import com.marketflow.order.domain.Cancellation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CancellationRepository extends JpaRepository<Cancellation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cancellation c where c.orderId = :orderId")
    Optional<Cancellation> findForUpdate(@Param("orderId") UUID orderId);
}
