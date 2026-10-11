package com.marketflow.order.persistence;

import com.marketflow.order.domain.saga.SagaTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SagaTransitionRepository extends JpaRepository<SagaTransition, Long> {
    List<SagaTransition> findByOrderIdOrderByIdAsc(UUID orderId);
}
