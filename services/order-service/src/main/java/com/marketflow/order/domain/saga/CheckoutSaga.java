package com.marketflow.order.domain.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persisted saga instance (one per order). Because state, attempt counter, deadline and outstanding compensations
 * are all in the database, a restart of order-service loses nothing: the timeout scanner simply resumes every
 * non-terminal saga whose deadline has passed.
 */
@Entity
@Table(name = "checkout_saga")
public class CheckoutSaga {

    public enum Compensation { INVENTORY, PAYMENT_VOID, PAYMENT_REFUND }

    @Id
    @Column(name = "order_id")
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaState state;
    @Column(nullable = false)
    private int attempt;
    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;
    @Column(name = "payment_token", nullable = false)
    private String paymentToken;
    @Column(name = "pending_compensations", nullable = false)
    private String pendingCompensations = "";
    @Column(name = "compensation_reason")
    private String compensationReason;
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    private Long version; // null until first persist, so Spring Data treats assigned-ID entities as new

    protected CheckoutSaga() {
    }

    public static CheckoutSaga start(UUID orderId, String paymentToken) {
        CheckoutSaga s = new CheckoutSaga();
        s.orderId = orderId;
        s.state = SagaState.CREATED;
        s.paymentToken = paymentToken;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        s.deadlineAt = s.createdAt;
        return s;
    }

    /**
     * Moves to {@code next}, resetting the attempt counter and arming a new deadline.
     *
     * @throws IllegalSagaTransitionException for transitions not in {@link SagaState}'s table
     */
    public SagaState moveTo(SagaState next, Duration timeout) {
        if (!state.canMoveTo(next)) {
            throw new IllegalSagaTransitionException(state, next);
        }
        SagaState previous = state;
        state = next;
        attempt = 0;
        Instant now = Instant.now();
        updatedAt = now;
        deadlineAt = now.plus(timeout);
        if (next.isTerminal()) {
            completedAt = now;
        }
        return previous;
    }

    /** Re-arms the deadline after a retried command. */
    public int retry(Duration timeout) {
        attempt++;
        updatedAt = Instant.now();
        deadlineAt = updatedAt.plus(timeout);
        return attempt;
    }

    public void beginCompensation(String reason, Set<Compensation> needed) {
        compensationReason = reason;
        pendingCompensations = needed.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /** @return true when no compensation is outstanding anymore */
    public boolean compensated(Compensation done) {
        Set<Compensation> left = pending();
        left.remove(done);
        pendingCompensations = left.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return left.isEmpty();
    }

    public Set<Compensation> pending() {
        if (pendingCompensations == null || pendingCompensations.isBlank()) {
            return EnumSet.noneOf(Compensation.class);
        }
        return Arrays.stream(pendingCompensations.split(",")).map(Compensation::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Compensation.class)));
    }

    public void recordError(String error) {
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 480));
    }

    public UUID getOrderId() {
        return orderId;
    }

    public SagaState getState() {
        return state;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public String getPaymentToken() {
        return paymentToken;
    }

    public String getCompensationReason() {
        return compensationReason;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
