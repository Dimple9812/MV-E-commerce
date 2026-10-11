package com.marketflow.order.domain.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only audit trail of every saga transition (what happened, why, triggered by which event). */
@Entity
@Table(name = "saga_transition")
public class SagaTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "from_state")
    private String fromState;
    @Column(name = "to_state", nullable = false)
    private String toState;
    @Column(nullable = false)
    private String trigger;
    @Column(name = "event_id")
    private UUID eventId;
    private String detail;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SagaTransition() {
    }

    public SagaTransition(UUID orderId, SagaState from, SagaState to, String trigger, UUID eventId, String detail) {
        this.orderId = orderId;
        this.fromState = from == null ? null : from.name();
        this.toState = to.name();
        this.trigger = trigger;
        this.eventId = eventId;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 290));
        this.occurredAt = Instant.now();
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }

    public String getTrigger() {
        return trigger;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
