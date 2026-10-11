package com.marketflow.payment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explicit SQL for payments, including the operation lease that prevents two concurrent provider calls. */
@Repository
public class PaymentStore {

    private final JdbcTemplate jdbc;

    public PaymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Payment(UUID id, UUID orderId, UUID customerId, BigDecimal amount, String currency, String status,
                          String provider, String providerPaymentId, String token, String inFlight,
                          Instant inFlightSince, String lastOperation, String failureCode, BigDecimal refundedAmount,
                          int reconAttempts, Instant authorizedAt, Instant capturedAt, Instant createdAt,
                          Instant updatedAt) {
    }

    static final RowMapper<Payment> MAPPER = (rs, i) -> new Payment(rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getBigDecimal("amount"),
            rs.getString("currency"), rs.getString("status"), rs.getString("provider"),
            rs.getString("provider_payment_id"), rs.getString("payment_token"), rs.getString("in_flight"),
            ts(rs.getTimestamp("in_flight_since")), rs.getString("last_operation"), rs.getString("failure_code"),
            rs.getBigDecimal("refunded_amount"), rs.getInt("recon_attempts"), ts(rs.getTimestamp("authorized_at")),
            ts(rs.getTimestamp("captured_at")), ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("updated_at")));

    public static RowMapper<Payment> mapper() {
        return MAPPER;
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public Optional<Payment> byOrder(UUID orderId, boolean lock) {
        return jdbc.query("SELECT * FROM payment WHERE order_id = ?" + (lock ? " FOR UPDATE" : ""), MAPPER, orderId)
                .stream().findFirst();
    }

    public Optional<Payment> byId(UUID id) {
        return jdbc.query("SELECT * FROM payment WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Payment> byProviderId(String providerPaymentId, boolean lock) {
        return jdbc.query("SELECT * FROM payment WHERE provider_payment_id = ?" + (lock ? " FOR UPDATE" : ""), MAPPER,
                providerPaymentId).stream().findFirst();
    }

    /** @return true if inserted; false if a payment for the order already exists */
    public boolean insertPending(UUID id, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                                 String provider, String token) {
        return jdbc.update("""
                INSERT INTO payment (id, order_id, customer_id, amount, currency, status, provider, payment_token,
                                     in_flight, in_flight_since, last_operation)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, 'AUTHORIZE', now(), 'AUTHORIZE')
                ON CONFLICT (order_id) DO NOTHING
                """, id, orderId, customerId, amount, currency, provider, token) == 1;
    }

    /**
     * Takes the operation lease: only succeeds when the payment is in one of {@code fromStatuses} and no other
     * operation is in flight. This is what prevents duplicate capture commands from capturing twice locally.
     */
    public Optional<Payment> lease(UUID orderId, String operation, String... fromStatuses) {
        List<Payment> rows = jdbc.query("""
                UPDATE payment SET in_flight = ?, in_flight_since = now(), last_operation = ?, updated_at = now()
                WHERE order_id = ? AND in_flight IS NULL AND status = ANY(?)
                RETURNING *
                """, ps -> {
            ps.setString(1, operation);
            ps.setString(2, operation);
            ps.setObject(3, orderId);
            ps.setArray(4, ps.getConnection().createArrayOf("varchar", fromStatuses));
        }, MAPPER);
        return rows.stream().findFirst();
    }

    public void release(UUID paymentId) {
        jdbc.update("UPDATE payment SET in_flight = NULL, in_flight_since = NULL, updated_at = now() WHERE id = ?",
                paymentId);
    }

    public void update(UUID paymentId, String status, String providerPaymentId, String failureCode) {
        jdbc.update("""
                UPDATE payment SET status = ?, provider_payment_id = coalesce(?, provider_payment_id),
                       failure_code = ?, in_flight = NULL, in_flight_since = NULL, updated_at = now(),
                       authorized_at = CASE WHEN ? = 'AUTHORIZED' AND authorized_at IS NULL THEN now() ELSE authorized_at END,
                       captured_at = CASE WHEN ? = 'CAPTURED' AND captured_at IS NULL THEN now() ELSE captured_at END,
                       version = version + 1
                WHERE id = ?
                """, status, providerPaymentId, failureCode, status, status, paymentId);
    }

    public void addRefunded(UUID paymentId, BigDecimal amount) {
        jdbc.update("""
                UPDATE payment SET refunded_amount = refunded_amount + ?,
                       status = CASE WHEN refunded_amount + ? >= amount THEN 'REFUNDED' ELSE 'PARTIALLY_REFUNDED' END,
                       in_flight = NULL, in_flight_since = NULL, updated_at = now(), version = version + 1
                WHERE id = ?
                """, amount, amount, paymentId);
    }

    public void attempt(UUID paymentId, String operation, String outcome, String reference, String error,
                        long durationMs) {
        jdbc.update("INSERT INTO payment_attempt (payment_id, operation, outcome, provider_reference, error_code, "
                + "duration_ms) VALUES (?, ?, ?, ?, ?, ?)", paymentId, operation, outcome, reference, error, durationMs);
    }

    public List<Payment> dueForReconciliation(int limit) {
        return jdbc.query("""
                SELECT * FROM payment
                WHERE (status IN ('PENDING', 'UNKNOWN') AND in_flight IS NULL AND updated_at < now() - interval '3 seconds')
                   OR (in_flight IS NOT NULL AND in_flight_since < now() - interval '60 seconds')
                ORDER BY updated_at LIMIT ?
                """, MAPPER, limit);
    }

    public void bumpReconAttempts(UUID paymentId) {
        jdbc.update("UPDATE payment SET recon_attempts = recon_attempts + 1, updated_at = now() WHERE id = ?",
                paymentId);
    }

    // ---------- refunds ----------

    public record Refund(UUID id, UUID paymentId, BigDecimal amount, String status, String reason, String refundKey,
                         String providerRefundId, Instant createdAt, Instant completedAt) {
    }

    static final RowMapper<Refund> REFUND = (rs, i) -> new Refund(rs.getObject("id", UUID.class),
            rs.getObject("payment_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("status"),
            rs.getString("reason"), rs.getString("refund_key"), rs.getString("provider_refund_id"),
            ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("completed_at")));

    public Optional<Refund> refundByKey(String key) {
        return jdbc.query("SELECT * FROM refund WHERE refund_key = ?", REFUND, key).stream().findFirst();
    }

    public boolean insertRefund(UUID id, UUID paymentId, BigDecimal amount, String reason, String key, UUID by) {
        return jdbc.update("INSERT INTO refund (id, payment_id, amount, status, reason, refund_key, requested_by) "
                + "VALUES (?, ?, ?, 'PENDING', ?, ?, ?) ON CONFLICT (refund_key) DO NOTHING", id, paymentId, amount,
                reason, key, by) == 1;
    }

    public void completeRefund(UUID refundId, String status, String providerRefundId) {
        jdbc.update("UPDATE refund SET status = ?, provider_refund_id = ?, completed_at = now() WHERE id = ?", status,
                providerRefundId, refundId);
    }

    public List<Refund> refunds(UUID paymentId) {
        return jdbc.query("SELECT * FROM refund WHERE payment_id = ? ORDER BY created_at", REFUND, paymentId);
    }

    public BigDecimal pendingRefunds(UUID paymentId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM refund WHERE payment_id = ? "
                + "AND status = 'PENDING'", BigDecimal.class, paymentId);
    }
}
