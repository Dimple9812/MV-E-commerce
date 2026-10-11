package com.marketflow.order.persistence;

import com.marketflow.order.domain.Order;
import com.marketflow.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Ownership is part of the query: another customer's order ID is indistinguishable from a missing one. */
    @EntityGraph(attributePaths = "suborders")
    @Query("select o from Order o where o.id = :id and o.customerId = :customerId")
    Optional<Order> findOwned(@Param("id") UUID id, @Param("customerId") UUID customerId);

    @EntityGraph(attributePaths = "suborders")
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findDetailed(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForUpdate(@Param("id") UUID id);

    Optional<Order> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Page<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
