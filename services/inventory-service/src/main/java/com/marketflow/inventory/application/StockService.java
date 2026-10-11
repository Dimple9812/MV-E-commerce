package com.marketflow.inventory.application;

import com.marketflow.inventory.application.StockLedger.Movement;
import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Vendor-facing stock management and read models. All vendor operations are scoped to the caller's vendor. */
@Service
public class StockService {

    private final JdbcTemplate jdbc;
    private final StockLedger ledger;
    private final AuditRecorder audit;

    public StockService(JdbcTemplate jdbc, StockLedger ledger, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.audit = audit;
    }

    public record ItemView(UUID variantId, UUID productId, String sku, String productName, String variantName,
                           int available, int reserved, int sold, int lowStockThreshold, boolean lowStock,
                           Instant updatedAt) {
    }

    public record MovementView(long id, String type, int deltaAvailable, int deltaReserved, int deltaSold,
                               String referenceType, String referenceId, String reason, Instant createdAt) {
    }

    public record Availability(UUID variantId, int available) {
    }

    private static final RowMapper<ItemView> ITEM = (rs, i) -> new ItemView(rs.getObject("variant_id", UUID.class),
            rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("product_name"),
            rs.getString("variant_name"), rs.getInt("available"), rs.getInt("reserved"), rs.getInt("sold"),
            rs.getInt("low_stock_threshold"), rs.getInt("available") <= rs.getInt("low_stock_threshold"),
            rs.getTimestamp("updated_at").toInstant());

    /** Maps the authenticated user to their APPROVED vendor via the local projection. */
    @Transactional(readOnly = true)
    public UUID vendorOf(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT vendor_id, status FROM vendor_projection WHERE owner_user_id = ?", userId);
        if (rows.isEmpty() || !"APPROVED".equals(rows.get(0).get("status"))) {
            throw ApiException.forbidden("No approved vendor account is linked to this user");
        }
        return (UUID) rows.get(0).get("vendor_id");
    }

    @Transactional(readOnly = true)
    public List<ItemView> items(UUID vendorId, boolean lowOnly, int page, int size) {
        return jdbc.query("SELECT * FROM inventory_item WHERE vendor_id = ? "
                + (lowOnly ? "AND available <= low_stock_threshold " : "")
                + "ORDER BY product_name, sku LIMIT ? OFFSET ?", ITEM, vendorId, size, (long) page * size);
    }

    @Transactional(readOnly = true)
    public long count(UUID vendorId, boolean lowOnly) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM inventory_item WHERE vendor_id = ? "
                + (lowOnly ? "AND available <= low_stock_threshold" : ""), Long.class, vendorId);
        return n == null ? 0 : n;
    }

    @Transactional(readOnly = true)
    public List<MovementView> movements(UUID vendorId, UUID variantId) {
        requireOwned(vendorId, variantId);
        return jdbc.query("""
                SELECT m.* FROM stock_movement m JOIN inventory_item i ON i.id = m.inventory_item_id
                WHERE i.variant_id = ? ORDER BY m.id DESC LIMIT 200
                """, (rs, i) -> new MovementView(rs.getLong("id"), rs.getString("movement_type"),
                rs.getInt("delta_available"), rs.getInt("delta_reserved"), rs.getInt("delta_sold"),
                rs.getString("reference_type"), rs.getString("reference_id"), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), variantId);
    }

    /**
     * Restock (positive delta) or write-off (negative delta). Reserved and sold units can never be adjusted away,
     * and available stock can never go below zero.
     */
    @Transactional
    public ItemView adjust(UUID userId, UUID vendorId, UUID variantId, int delta, String reason) {
        if (delta == 0) {
            throw ApiException.badRequest("invalid_quantity", "Adjustment must not be zero");
        }
        requireOwned(vendorId, variantId);
        ledger.apply(variantId, delta, 0, 0, new Movement("ADJUST", "ADJUSTMENT", UUID.randomUUID().toString(),
                        userId, reason))
                .orElseThrow(() -> ApiException.unprocessable("insufficient_stock",
                        "Adjustment would make available stock negative"));
        audit.record(userId, "VENDOR", "inventory.adjusted", "InventoryItem", variantId.toString(),
                Map.of("delta", delta, "reason", reason));
        return item(variantId);
    }

    @Transactional
    public ItemView setThreshold(UUID vendorId, UUID variantId, int threshold) {
        requireOwned(vendorId, variantId);
        jdbc.update("UPDATE inventory_item SET low_stock_threshold = ?, updated_at = now() WHERE variant_id = ?",
                threshold, variantId);
        return item(variantId);
    }

    @Transactional(readOnly = true)
    public List<Availability> availability(List<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT variant_id, available FROM inventory_item WHERE variant_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", variantIds.toArray())),
                (rs, i) -> new Availability(rs.getObject("variant_id", UUID.class), rs.getInt("available")));
    }

    @Transactional(readOnly = true)
    public List<ItemView> lowStockAcrossPlatform(int limit) {
        return jdbc.query("SELECT * FROM inventory_item WHERE available <= low_stock_threshold "
                + "ORDER BY available, updated_at DESC LIMIT ?", ITEM, limit);
    }

    public record InvariantViolation(UUID variantId, int available, int reserved, int sold, long sumAvailable,
                                     long sumReserved, long sumSold) {
    }

    /** Verifies the accounting invariant for every item: current quantities == sum of movement deltas. */
    @Transactional(readOnly = true)
    public List<InvariantViolation> verifyLedger() {
        return jdbc.query("""
                SELECT i.variant_id, i.available, i.reserved, i.sold,
                       coalesce(sum(m.delta_available), 0) sa, coalesce(sum(m.delta_reserved), 0) sr,
                       coalesce(sum(m.delta_sold), 0) ss
                FROM inventory_item i LEFT JOIN stock_movement m ON m.inventory_item_id = i.id
                GROUP BY i.id
                HAVING i.available <> coalesce(sum(m.delta_available), 0)
                    OR i.reserved <> coalesce(sum(m.delta_reserved), 0)
                    OR i.sold <> coalesce(sum(m.delta_sold), 0)
                """, (rs, i) -> new InvariantViolation(rs.getObject("variant_id", UUID.class), rs.getInt("available"),
                rs.getInt("reserved"), rs.getInt("sold"), rs.getLong("sa"), rs.getLong("sr"), rs.getLong("ss")));
    }

    private ItemView item(UUID variantId) {
        return jdbc.queryForObject("SELECT * FROM inventory_item WHERE variant_id = ?", ITEM, variantId);
    }

    private void requireOwned(UUID vendorId, UUID variantId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM inventory_item WHERE variant_id = ? AND vendor_id = ?",
                Integer.class, variantId, vendorId);
        if (n == null || n == 0) {
            throw ApiException.notFound("Inventory item"); // same answer for "missing" and "someone else's"
        }
    }
}
