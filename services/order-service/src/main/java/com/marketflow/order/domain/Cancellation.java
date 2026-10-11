package com.marketflow.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Post-confirmation cancellation: two independent compensations (refund payment, restock inventory) that must
 * both be acknowledged before the order becomes CANCELLED.
 */
@Entity
@Table(name = "cancellation")
public class Cancellation {

    @Id
    @Column(name = "order_id")
    private UUID orderId;
    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;
    @Column(nullable = false)
    private String reason;
    @Column(name = "refund_done", nullable = false)
    private boolean refundDone;
    @Column(name = "restock_done", nullable = false)
    private boolean restockDone;
    @Column(name = "failed_reason")
    private String failedReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected Cancellation() {
    }

    public Cancellation(UUID orderId, UUID requestedBy, String reason) {
        this.orderId = orderId;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    /** @return true when both compensations are done (and it was not already complete) */
    public boolean markRefunded() {
        boolean wasComplete = isComplete();
        refundDone = true;
        return complete(wasComplete);
    }

    public boolean markRestocked() {
        boolean wasComplete = isComplete();
        restockDone = true;
        return complete(wasComplete);
    }

    private boolean complete(boolean wasComplete) {
        if (!wasComplete && isComplete()) {
            completedAt = Instant.now();
            return true;
        }
        return false;
    }

    public boolean isComplete() {
        return refundDone && restockDone;
    }

    public void fail(String reason) {
        this.failedReason = reason;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getReason() {
        return reason;
    }

    public boolean isRefundDone() {
        return refundDone;
    }

    public boolean isRestockDone() {
        return restockDone;
    }

    public String getFailedReason() {
        return failedReason;
    }
}
