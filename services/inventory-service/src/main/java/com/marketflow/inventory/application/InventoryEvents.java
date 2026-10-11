package com.marketflow.inventory.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Messages on {@code inventory.commands} (consumed) and {@code inventory.events} (produced).
 * Reservation messages are keyed by orderId so every message of one checkout lands on one partition in order.
 * Stock messages are keyed by variantId.
 */
public final class InventoryEvents {

    public static final String COMMANDS = "inventory.commands";
    public static final String TOPIC = "inventory.events";

    private InventoryEvents() {
    }

    // ---- commands (from order-service saga) ----

    public record Line(UUID variantId, String sku, int quantity) {
    }

    public record ReservationCommand(UUID orderId, List<Line> lines, String reason) {
    }

    // ---- results ----

    public record ReservedLine(UUID variantId, String sku, int quantity) {
    }

    public record InventoryReserved(UUID orderId, UUID reservationId, Instant expiresAt, List<ReservedLine> lines) {
    }

    public record ShortLine(UUID variantId, String sku, int requested, int available) {
    }

    public record InventoryReservationFailed(UUID orderId, String reason, List<ShortLine> unavailable) {
    }

    public record ReservationOutcome(UUID orderId, UUID reservationId, String status, String reason) {
    }

    // ---- stock notifications ----

    public record StockLevelChanged(UUID variantId, UUID productId, UUID vendorId, String sku, int available,
                                    int reserved, int sold, long stockVersion) {
    }

    public record LowStockDetected(UUID variantId, UUID productId, UUID vendorId, String sku, String productName,
                                   int available, int threshold) {
    }
}
