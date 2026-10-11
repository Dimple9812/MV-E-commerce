package com.marketflow.order.persistence;

import com.marketflow.order.domain.saga.CheckoutSaga;
import com.marketflow.order.domain.saga.SagaState;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaRepository extends JpaRepository<CheckoutSaga, UUID> {

    /** Serialises concurrent replies for the same saga (e.g. duplicate + timeout firing at once). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CheckoutSaga s where s.orderId = :orderId")
    Optional<CheckoutSaga> findForUpdate(@Param("orderId") UUID orderId);

    @Query(value = """
            SELECT order_id FROM checkout_saga
            WHERE deadline_at < now() AND state NOT IN ('CONFIRMED', 'CANCELLED', 'FAILED')
            ORDER BY deadline_at LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDue(@Param("limit") int limit);

    List<CheckoutSaga> findByStateOrderByUpdatedAtDesc(SagaState state, Pageable pageable);

    @Query("select s.state as state, count(s) as total from CheckoutSaga s group by s.state")
    List<StateCount> countByState();

    interface StateCount {
        SagaState getState();

        long getTotal();
    }
}
