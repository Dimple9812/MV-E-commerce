package com.marketflow.vendor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vendor_status_history")
public class StatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;
    @Column(name = "from_status")
    private String fromStatus;
    @Column(name = "to_status", nullable = false)
    private String toStatus;
    @Column(name = "actor_id")
    private UUID actorId;
    private String reason;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected StatusHistory() {
    }

    public StatusHistory(UUID vendorId, Vendor.Status from, Vendor.Status to, UUID actorId, String reason) {
        this.vendorId = vendorId;
        this.fromStatus = from == null ? null : from.name();
        this.toStatus = to.name();
        this.actorId = actorId;
        this.reason = reason;
        this.occurredAt = Instant.now();
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
