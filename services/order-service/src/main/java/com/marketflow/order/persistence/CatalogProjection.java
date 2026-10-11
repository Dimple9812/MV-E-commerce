package com.marketflow.order.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * order-service's local, event-fed copy of the catalog and vendor data it needs to price a checkout without a
 * synchronous call to catalog-service. Upserts are version-guarded so redelivered or out-of-order events cannot
 * roll the projection back to an older state.
 */
@Repository
public class CatalogProjection {

    private final JdbcTemplate jdbc;

    public CatalogProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Variant(UUID variantId, UUID productId, UUID vendorId, String sku, String variantName,
                          String productName, String productStatus, String currency, int maxPerOrder,
                          String imageUrl, BigDecimal price, boolean active, String vendorName, String vendorStatus,
                          BigDecimal commissionRate) {
    }

    public Map<UUID, Variant> variants(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Variant> rows = jdbc.query("""
                SELECT v.variant_id, v.product_id, p.vendor_id, v.sku, v.name AS variant_name, p.name AS product_name,
                       p.status AS product_status, p.currency, p.max_per_order, p.image_url, v.price, v.active,
                       vp.business_name, vp.status AS vendor_status, vp.commission_rate
                FROM variant_projection v
                JOIN product_projection p ON p.product_id = v.product_id
                LEFT JOIN vendor_projection vp ON vp.vendor_id = p.vendor_id
                WHERE v.variant_id = ANY(?)
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                (rs, i) -> new Variant(rs.getObject("variant_id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getObject("vendor_id", UUID.class), rs.getString("sku"), rs.getString("variant_name"),
                        rs.getString("product_name"), rs.getString("product_status"), rs.getString("currency"),
                        rs.getInt("max_per_order"), rs.getString("image_url"), rs.getBigDecimal("price"),
                        rs.getBoolean("active"), rs.getString("business_name"), rs.getString("vendor_status"),
                        rs.getBigDecimal("commission_rate")));
        return rows.stream().collect(Collectors.toMap(Variant::variantId, v -> v));
    }

    public record VariantSnapshot(UUID variantId, String sku, String name, BigDecimal price, boolean active) {
    }

    /** @return true if applied (newer version) */
    public boolean upsertProduct(UUID productId, UUID vendorId, String name, String status, String currency,
                                 int maxPerOrder, String imageUrl, long version, List<VariantSnapshot> variants) {
        int changed = jdbc.update("""
                INSERT INTO product_projection (product_id, vendor_id, name, status, currency, max_per_order, image_url,
                                                product_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_id) DO UPDATE SET vendor_id = EXCLUDED.vendor_id, name = EXCLUDED.name,
                    status = EXCLUDED.status, currency = EXCLUDED.currency, max_per_order = EXCLUDED.max_per_order,
                    image_url = EXCLUDED.image_url, product_version = EXCLUDED.product_version, updated_at = now()
                WHERE product_projection.product_version < EXCLUDED.product_version
                """, productId, vendorId, name, status, currency, maxPerOrder, imageUrl, version);
        if (changed == 0) {
            return false;
        }
        for (VariantSnapshot v : variants) {
            jdbc.update("""
                    INSERT INTO variant_projection (variant_id, product_id, sku, name, price, active)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (variant_id) DO UPDATE SET sku = EXCLUDED.sku, name = EXCLUDED.name,
                        price = EXCLUDED.price, active = EXCLUDED.active
                    """, v.variantId(), productId, v.sku(), v.name(), v.price(), v.active());
        }
        return true;
    }

    public void upsertVendor(UUID vendorId, UUID ownerUserId, String name, String status, BigDecimal commission,
                             long version) {
        jdbc.update("""
                INSERT INTO vendor_projection (vendor_id, owner_user_id, business_name, status, commission_rate,
                                               vendor_version)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (vendor_id) DO UPDATE SET business_name = EXCLUDED.business_name,
                    status = EXCLUDED.status, commission_rate = EXCLUDED.commission_rate,
                    vendor_version = EXCLUDED.vendor_version, updated_at = now()
                WHERE vendor_projection.vendor_version < EXCLUDED.vendor_version
                """, vendorId, ownerUserId, name, status, commission, version);
    }

    public Optional<UUID> approvedVendorOf(UUID ownerUserId) {
        List<UUID> ids = jdbc.queryForList("SELECT vendor_id FROM vendor_projection WHERE owner_user_id = ? "
                + "AND status = 'APPROVED'", UUID.class, ownerUserId);
        return ids.stream().findFirst();
    }
}
