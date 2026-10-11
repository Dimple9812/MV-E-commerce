package com.marketflow.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "suborder_id")
    private UUID suborderId;
    @Column(name = "from_status")
    private String fromStatus;
    @Column(name = "to_status", nullable = false)
    private String toStatus;
    private String reason;
    @Column(name = "actor_id")
    private UUID actorId;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OrderStatusHistory() {
    }

    public OrderStatusHistory(UUID orderId, UUID suborderId, String from, String to, String reason, UUID actorId) {
        this.orderId = orderId;
        this.suborderId = suborderId;
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 290));
        this.actorId = actorId;
        this.occurredAt = Instant.now();
    }

    public UUID getSuborderId() {
        return suborderId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
