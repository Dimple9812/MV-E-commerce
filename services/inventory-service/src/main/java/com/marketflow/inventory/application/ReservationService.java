package com.marketflow.inventory.application;

import com.marketflow.inventory.application.InventoryEvents.Line;
import com.marketflow.inventory.application.InventoryEvents.ReservationOutcome;
import com.marketflow.inventory.application.InventoryEvents.ReservedLine;
import com.marketflow.inventory.application.InventoryEvents.ShortLine;
import com.marketflow.inventory.application.StockLedger.Movement;
import com.marketflow.platform.events.NonRetryableEventException;
import com.marketflow.platform.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles saga commands. Every command is idempotent on the business key {@code order_id}:
 * <ul>
 *   <li>ReserveInventory for an order that already has a reservation does not touch stock again; it re-publishes
 *       the existing outcome so a saga that lost the first reply still gets one.</li>
 *   <li>Release/Confirm/Restock only move stock when the reservation is in the matching state; repeating them is a
 *       no-op that re-publishes the outcome.</li>
 *   <li>A Release that arrives BEFORE its Reserve (possible after a saga timeout) writes a RELEASED tombstone, so
 *       the late Reserve cannot reserve stock nobody will ever release.</li>
 * </ul>
 * All lines of one order are reserved in a single local transaction: either every line is reserved or none is,
 * so a multi-vendor checkout never needs partial-reservation compensation inside inventory.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final JdbcTemplate jdbc;
    private final StockLedger ledger;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Duration reservationTtl;

    public ReservationService(JdbcTemplate jdbc, StockLedger ledger, OutboxWriter outbox, TransactionTemplate tx,
                              MeterRegistry meters,
                              @Value("${marketflow.inventory.reservation-ttl:15m}") Duration reservationTtl) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = tx;
        this.meters = meters;
        this.reservationTtl = reservationTtl;
    }

    private record Reservation(UUID id, UUID orderId, String status, String failureReason, Instant expiresAt) {
    }

    private record ReservationLine(UUID variantId, int quantity, String sku) {
    }

    static final class InsufficientStock extends RuntimeException {
        final List<ShortLine> lines;

        InsufficientStock(List<ShortLine> lines) {
            super("insufficient stock", null, false, false);
            this.lines = lines;
        }
    }

    // ------------------------------------------------------------------ reserve

    public void reserve(UUID orderId, List<Line> requested, String causationId) {
        if (requested == null || requested.isEmpty() || requested.stream().anyMatch(l -> l.quantity() <= 0)) {
            throw new NonRetryableEventException("ReserveInventory with no lines or non-positive quantity");
        }
        // merge duplicates and lock rows in a deterministic order (variantId) to avoid deadlocks between checkouts
        Map<UUID, Line> merged = new LinkedHashMap<>();
        requested.stream().sorted(Comparator.comparing(Line::variantId)).forEach(l -> merged.merge(l.variantId(), l,
                (a, b) -> new Line(a.variantId(), a.sku(), a.quantity() + b.quantity())));
        try {
            tx.executeWithoutResult(s -> {
                Optional<Reservation> existing = find(orderId, true);
                if (existing.isPresent()) {
                    republish(existing.get(), causationId);
                    return;
                }
                UUID reservationId = UUID.randomUUID();
                Instant expiresAt = Instant.now().plus(reservationTtl);
                int inserted = jdbc.update("INSERT INTO reservation (id, order_id, status, expires_at) "
                        + "VALUES (?, ?, 'RESERVED', ?) ON CONFLICT (order_id) DO NOTHING", reservationId, orderId,
                        java.sql.Timestamp.from(expiresAt));
                if (inserted == 0) { // a concurrent duplicate command won the race
                    find(orderId, true).ifPresent(r -> republish(r, causationId));
                    return;
                }
                List<ShortLine> shortLines = new ArrayList<>();
                List<ReservedLine> reserved = new ArrayList<>();
                for (Line l : merged.values()) {
                    Optional<StockLedger.ItemState> state = ledger.apply(l.variantId(), -l.quantity(), l.quantity(), 0,
                            new Movement("RESERVE", "ORDER", orderId.toString(), null, null));
                    if (state.isEmpty()) {
                        shortLines.add(new ShortLine(l.variantId(), l.sku(), l.quantity(), ledger.available(l.variantId())));
                        continue;
                    }
                    jdbc.update("INSERT INTO reservation_line (reservation_id, inventory_item_id, variant_id, quantity) "
                            + "VALUES (?, ?, ?, ?)", reservationId, state.get().itemId(), l.variantId(), l.quantity());
                    reserved.add(new ReservedLine(l.variantId(), state.get().sku(), l.quantity()));
                }
                if (!shortLines.isEmpty()) {
                    throw new InsufficientStock(shortLines); // rolls back every line of this order
                }
                outbox.append(InventoryEvents.TOPIC, "InventoryReserved", 1, "Reservation", orderId.toString(),
                        new InventoryEvents.InventoryReserved(orderId, reservationId, expiresAt, reserved),
                        causationId);
                meters.counter("inventory.reservations", "outcome", "reserved").increment();
            });
        } catch (InsufficientStock e) {
            recordFailure(orderId, "INSUFFICIENT_STOCK", e.lines, causationId);
        }
    }

    private void recordFailure(UUID orderId, String reason, List<ShortLine> lines, String causationId) {
        tx.executeWithoutResult(s -> {
            int inserted = jdbc.update("INSERT INTO reservation (id, order_id, status, failure_reason, expires_at) "
                    + "VALUES (?, ?, 'FAILED', ?, now()) ON CONFLICT (order_id) DO NOTHING", UUID.randomUUID(),
                    orderId, reason);
            if (inserted == 0) {
                find(orderId, true).ifPresent(r -> republish(r, causationId));
                return;
            }
            outbox.append(InventoryEvents.TOPIC, "InventoryReservationFailed", 1, "Reservation", orderId.toString(),
                    new InventoryEvents.InventoryReservationFailed(orderId, reason, lines), causationId);
            meters.counter("inventory.reservations", "outcome", "failed").increment();
        });
    }

    // ------------------------------------------------------------------ confirm

    public void confirm(UUID orderId, String causationId) {
        tx.executeWithoutResult(s -> {
            Optional<Reservation> found = find(orderId, true);
            if (found.isEmpty()) {
                outcome(orderId, null, "InventoryConfirmationFailed", "NOT_FOUND", "NO_RESERVATION", causationId);
                return;
            }
            Reservation r = found.get();
            switch (r.status()) {
                case "CONFIRMED", "RETURNED" -> republish(r, causationId);
                case "RESERVED" -> {
                    for (ReservationLine l : lines(r.id())) {
                        ledger.apply(l.variantId(), 0, -l.quantity(), l.quantity(),
                                new Movement("CONFIRM", "ORDER", orderId.toString(), null, null))
                                .orElseThrow(() -> new IllegalStateException("reserved quantity missing for " + l));
                    }
                    setStatus(r.id(), "CONFIRMED", null);
                    outcome(orderId, r.id(), "InventoryReservationConfirmed", "CONFIRMED", null, causationId);
                }
                case "RELEASED", "EXPIRED" -> confirmLate(r, causationId);
                default -> outcome(orderId, r.id(), "InventoryConfirmationFailed", r.status(), r.failureReason(),
                        causationId);
            }
        });
    }

    /**
     * The reservation expired before payment completed. Try to take the units straight from available stock;
     * if that is no longer possible the saga must refund the payment.
     */
    private void confirmLate(Reservation r, String causationId) {
        List<ReservationLine> lines = lines(r.id());
        // lock the item rows first (deterministic order), then decide - no exception-driven rollback needed
        Map<UUID, Integer> available = new LinkedHashMap<>();
        for (ReservationLine l : lines) {
            available.put(l.variantId(), jdbc.queryForObject(
                    "SELECT available FROM inventory_item WHERE variant_id = ? FOR UPDATE", Integer.class,
                    l.variantId()));
        }
        boolean enough = lines.stream().allMatch(l -> available.get(l.variantId()) >= l.quantity());
        if (!enough) {
            outcome(r.orderId(), r.id(), "InventoryConfirmationFailed", r.status(), "STOCK_GONE_AFTER_EXPIRY",
                    causationId);
            meters.counter("inventory.reservations", "outcome", "late_confirm_failed").increment();
            return;
        }
        for (ReservationLine l : lines) {
            ledger.apply(l.variantId(), -l.quantity(), 0, l.quantity(),
                            new Movement("CONFIRM_LATE", "ORDER", r.orderId().toString(), null,
                                    "reservation had " + r.status()))
                    .orElseThrow(() -> new IllegalStateException("locked stock changed for " + l));
        }
        setStatus(r.id(), "CONFIRMED", null);
        outcome(r.orderId(), r.id(), "InventoryReservationConfirmed", "CONFIRMED", "LATE", causationId);
    }

    // ------------------------------------------------------------------ release / expire

    public void release(UUID orderId, String reason, String causationId) {
        tx.executeWithoutResult(s -> releaseInTx(orderId, reason, "RELEASED", "RELEASE", causationId));
    }

    private void releaseInTx(UUID orderId, String reason, String newStatus, String movement, String causationId) {
        Optional<Reservation> found = find(orderId, true);
        if (found.isEmpty()) {
            // tombstone: makes a late ReserveInventory for this order a no-op
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO reservation (id, order_id, status, failure_reason, expires_at) "
                    + "VALUES (?, ?, 'RELEASED', ?, now()) ON CONFLICT (order_id) DO NOTHING", id, orderId,
                    "released before reserve: " + reason);
            outcome(orderId, id, "InventoryReservationReleased", "RELEASED", reason, causationId);
            return;
        }
        Reservation r = found.get();
        if (!"RESERVED".equals(r.status())) {
            republish(r, causationId); // already released / failed / confirmed: never move stock twice
            return;
        }
        for (ReservationLine l : lines(r.id())) {
            ledger.apply(l.variantId(), l.quantity(), -l.quantity(), 0,
                    new Movement(movement, "ORDER", orderId.toString(), null, reason))
                    .orElseThrow(() -> new IllegalStateException("reserved quantity missing for " + l));
        }
        setStatus(r.id(), newStatus, reason);
        outcome(orderId, r.id(), "InventoryReservationReleased", newStatus, reason, causationId);
        meters.counter("inventory.reservations", "outcome", newStatus.toLowerCase()).increment();
    }

    @Scheduled(fixedDelayString = "${marketflow.inventory.expiry-scan:30s}")
    public void expireReservations() {
        List<UUID> due = jdbc.queryForList("SELECT order_id FROM reservation WHERE status = 'RESERVED' "
                + "AND expires_at < now() ORDER BY expires_at LIMIT 100", UUID.class);
        for (UUID orderId : due) {
            try {
                tx.executeWithoutResult(s -> releaseInTx(orderId, "EXPIRED", "EXPIRED", "EXPIRE", null));
            } catch (RuntimeException e) {
                log.warn("Expiring reservation for order {} failed: {}", orderId, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ restock (cancellation after confirmation)

    public void restock(UUID orderId, String reason, String causationId) {
        tx.executeWithoutResult(s -> {
            Optional<Reservation> found = find(orderId, true);
            if (found.isEmpty()) {
                outcome(orderId, null, "InventoryRestocked", "NOT_FOUND", "NO_RESERVATION", causationId);
                return;
            }
            Reservation r = found.get();
            if ("RESERVED".equals(r.status())) {
                releaseInTx(orderId, reason, "RELEASED", "RELEASE", causationId);
                return;
            }
            if (!"CONFIRMED".equals(r.status())) {
                republish(r, causationId);
                return;
            }
            for (ReservationLine l : lines(r.id())) {
                ledger.apply(l.variantId(), l.quantity(), 0, -l.quantity(),
                        new Movement("RETURN", "ORDER", orderId.toString(), null, reason))
                        .orElseThrow(() -> new IllegalStateException("sold quantity missing for " + l));
            }
            setStatus(r.id(), "RETURNED", reason);
            outcome(orderId, r.id(), "InventoryRestocked", "RETURNED", reason, causationId);
        });
    }

    // ------------------------------------------------------------------ helpers

    private Optional<Reservation> find(UUID orderId, boolean forUpdate) {
        List<Reservation> rows = jdbc.query("SELECT id, order_id, status, failure_reason, expires_at FROM reservation "
                        + "WHERE order_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new Reservation(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getString("status"), rs.getString("failure_reason"),
                        rs.getTimestamp("expires_at").toInstant()), orderId);
        return rows.stream().findFirst();
    }

    private List<ReservationLine> lines(UUID reservationId) {
        return jdbc.query("SELECT l.variant_id, l.quantity, i.sku FROM reservation_line l "
                        + "JOIN inventory_item i ON i.id = l.inventory_item_id WHERE l.reservation_id = ? "
                        + "ORDER BY l.variant_id",
                (rs, i) -> new ReservationLine(rs.getObject("variant_id", UUID.class), rs.getInt("quantity"),
                        rs.getString("sku")), reservationId);
    }

    private void setStatus(UUID id, String status, String reason) {
        jdbc.update("UPDATE reservation SET status = ?, failure_reason = coalesce(?, failure_reason), "
                + "updated_at = now() WHERE id = ?", status, reason, id);
    }

    private void outcome(UUID orderId, UUID reservationId, String type, String status, String reason,
                         String causationId) {
        outbox.append(InventoryEvents.TOPIC, type, 1, "Reservation", orderId.toString(),
                new ReservationOutcome(orderId, reservationId, status, reason), causationId);
    }

    /** Re-publishes the current outcome of an existing reservation (idempotent command replay). */
    private void republish(Reservation r, String causationId) {
        switch (r.status()) {
            case "RESERVED" -> outbox.append(InventoryEvents.TOPIC, "InventoryReserved", 1, "Reservation",
                    r.orderId().toString(), new InventoryEvents.InventoryReserved(r.orderId(), r.id(), r.expiresAt(),
                            lines(r.id()).stream().map(l -> new ReservedLine(l.variantId(), l.sku(), l.quantity()))
                                    .toList()), causationId);
            case "FAILED" -> outbox.append(InventoryEvents.TOPIC, "InventoryReservationFailed", 1, "Reservation",
                    r.orderId().toString(), new InventoryEvents.InventoryReservationFailed(r.orderId(),
                            r.failureReason(), List.of()), causationId);
            case "CONFIRMED" -> outcome(r.orderId(), r.id(), "InventoryReservationConfirmed", "CONFIRMED", null,
                    causationId);
            case "RETURNED" -> outcome(r.orderId(), r.id(), "InventoryRestocked", "RETURNED", r.failureReason(),
                    causationId);
            default -> outcome(r.orderId(), r.id(), "InventoryReservationReleased", r.status(), r.failureReason(),
                    causationId);
        }
    }
}
