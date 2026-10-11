package com.marketflow.inventory.application;

import com.marketflow.platform.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only code path that changes stock quantities.
 *
 * <p>Each change is ONE conditional UPDATE: {@code ... WHERE variant_id = ? AND available + Δ >= 0 ...}.
 * PostgreSQL takes a row lock for the UPDATE and re-evaluates the WHERE clause against the latest committed row
 * if it had to wait, so two concurrent checkouts for the last unit cannot both succeed - the second sees
 * {@code available = 0} and updates zero rows. The CHECK (available >= 0) constraints are a second safety net.
 * No Redis/distributed lock is involved in correctness.
 */
@Component
public class StockLedger {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public StockLedger(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record ItemState(UUID itemId, UUID variantId, UUID productId, UUID vendorId, String sku, String productName,
                            int available, int reserved, int sold, int threshold, long version) {
    }

    public record Movement(String type, String referenceType, String referenceId, UUID actorId, String reason) {
    }

    /**
     * Applies the deltas atomically.
     *
     * @return the new state, or empty if the change would make any quantity negative (or the item is unknown)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ItemState> apply(UUID variantId, int dAvailable, int dReserved, int dSold, Movement m) {
        List<ItemState> rows = jdbc.query("""
                UPDATE inventory_item
                   SET available = available + ?, reserved = reserved + ?, sold = sold + ?,
                       version = version + 1, updated_at = now()
                 WHERE variant_id = ?
                   AND available + ? >= 0 AND reserved + ? >= 0 AND sold + ? >= 0
                RETURNING id, variant_id, product_id, vendor_id, sku, product_name, available, reserved, sold,
                          low_stock_threshold, version
                """, (rs, i) -> new ItemState(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                        rs.getObject("product_id", UUID.class), rs.getObject("vendor_id", UUID.class),
                        rs.getString("sku"), rs.getString("product_name"), rs.getInt("available"),
                        rs.getInt("reserved"), rs.getInt("sold"), rs.getInt("low_stock_threshold"),
                        rs.getLong("version")),
                dAvailable, dReserved, dSold, variantId, dAvailable, dReserved, dSold);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ItemState s = rows.get(0);
        jdbc.update("""
                INSERT INTO stock_movement (inventory_item_id, movement_type, delta_available, delta_reserved,
                                            delta_sold, reference_type, reference_id, actor_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, s.itemId(), m.type(), dAvailable, dReserved, dSold, m.referenceType(), m.referenceId(),
                m.actorId(), m.reason());
        if (dAvailable != 0) {
            outbox.append(InventoryEvents.TOPIC, "StockLevelChanged", 1, "InventoryItem", s.variantId().toString(),
                    new InventoryEvents.StockLevelChanged(s.variantId(), s.productId(), s.vendorId(), s.sku(),
                            s.available(), s.reserved(), s.sold(), s.version()));
            int before = s.available() - dAvailable;
            if (s.available() <= s.threshold() && before > s.threshold()) {
                outbox.append(InventoryEvents.TOPIC, "LowStockDetected", 1, "InventoryItem",
                        s.variantId().toString(), new InventoryEvents.LowStockDetected(s.variantId(), s.productId(),
                                s.vendorId(), s.sku(), s.productName(), s.available(), s.threshold()));
            }
        }
        return Optional.of(s);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int available(UUID variantId) {
        List<Integer> v = jdbc.queryForList("SELECT available FROM inventory_item WHERE variant_id = ?",
                Integer.class, variantId);
        return v.isEmpty() ? 0 : v.get(0);
    }
}
