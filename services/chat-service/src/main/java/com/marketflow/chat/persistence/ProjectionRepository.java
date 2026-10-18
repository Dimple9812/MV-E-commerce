package com.marketflow.chat.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.chat.domain.VendorRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Local, eventually consistent copies of other services' data (vendor.events, catalog.product.events,
 * order.events). Each upsert is guarded by the producer's version, so an old or duplicate event never overwrites
 * a newer state. The API never writes these tables.
 */
@Repository
public class ProjectionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProjectionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------- vendors ----------

    public void upsertVendor(UUID vendorId, UUID ownerUserId, String businessName, String slug, String status,
                             long version) {
        jdbc.update("""
                INSERT INTO vendor_projection (vendor_id, owner_user_id, business_name, slug, status, vendor_version)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (vendor_id) DO UPDATE SET business_name = EXCLUDED.business_name, slug = EXCLUDED.slug,
                    status = EXCLUDED.status, vendor_version = EXCLUDED.vendor_version, updated_at = now()
                WHERE vendor_projection.vendor_version < EXCLUDED.vendor_version
                """, vendorId, ownerUserId, businessName, slug, status, version);
    }

    public Optional<VendorRef> vendor(UUID vendorId) {
        return jdbc.query("SELECT * FROM vendor_projection WHERE vendor_id = ?", (rs, i) -> new VendorRef(
                rs.getObject("vendor_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                rs.getString("business_name"), rs.getString("slug"), rs.getString("status")), vendorId)
                .stream().findFirst();
    }

    public Optional<VendorRef> vendorOwnedBy(UUID userId) {
        return jdbc.query("SELECT * FROM vendor_projection WHERE owner_user_id = ?", (rs, i) -> new VendorRef(
                rs.getObject("vendor_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                rs.getString("business_name"), rs.getString("slug"), rs.getString("status")), userId)
                .stream().findFirst();
    }

    // ---------- products ----------

    public record ProductRef(UUID productId, UUID vendorId, String name, String slug, String imageUrl,
                             BigDecimal minPrice, String currency, String status) {
        public boolean visible() {
            return "PUBLISHED".equals(status);
        }
    }

    public void upsertProduct(UUID productId, UUID vendorId, String name, String slug, String imageUrl,
                              BigDecimal minPrice, String currency, String status, long version) {
        jdbc.update("""
                INSERT INTO product_projection (product_id, vendor_id, name, slug, image_url, min_price, currency,
                                                status, product_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_id) DO UPDATE SET name = EXCLUDED.name, slug = EXCLUDED.slug,
                    image_url = EXCLUDED.image_url, min_price = EXCLUDED.min_price, currency = EXCLUDED.currency,
                    status = EXCLUDED.status, product_version = EXCLUDED.product_version, updated_at = now()
                WHERE product_projection.product_version < EXCLUDED.product_version
                """, productId, vendorId, name, slug, imageUrl, minPrice, currency, status, version);
    }

    public Optional<ProductRef> product(UUID productId) {
        return jdbc.query("SELECT * FROM product_projection WHERE product_id = ?", (rs, i) -> new ProductRef(
                rs.getObject("product_id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getString("name"),
                rs.getString("slug"), rs.getString("image_url"), rs.getBigDecimal("min_price"),
                rs.getString("currency"), rs.getString("status")), productId).stream().findFirst();
    }

    // ---------- suborders ----------

    public record SuborderRef(UUID suborderId, UUID orderId, UUID customerId, UUID vendorId, String status,
                              BigDecimal total, String currency, JsonNode items, Instant placedAt) {
    }

    /** Order events are keyed by orderId, so they arrive in order per order; status is simply overwritten. */
    public void upsertSuborder(UUID suborderId, UUID orderId, UUID customerId, UUID vendorId, String status,
                               BigDecimal total, String currency, JsonNode items, Instant placedAt) {
        jdbc.update("""
                INSERT INTO suborder_projection (suborder_id, order_id, customer_id, vendor_id, status, total,
                                                 currency, item_summary, placed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (suborder_id) DO UPDATE SET status = EXCLUDED.status, total = EXCLUDED.total,
                    item_summary = EXCLUDED.item_summary, updated_at = now()
                """, suborderId, orderId, customerId, vendorId, status, total, currency, write(items),
                Timestamp.from(placedAt));
    }

    public void setSuborderStatus(UUID suborderId, String status) {
        jdbc.update("UPDATE suborder_projection SET status = ?, updated_at = now() WHERE suborder_id = ?", status,
                suborderId);
    }

    public void setOrderStatus(UUID orderId, String status) {
        jdbc.update("UPDATE suborder_projection SET status = ?, updated_at = now() WHERE order_id = ?", status,
                orderId);
    }

    public Optional<SuborderRef> suborder(UUID suborderId) {
        return jdbc.query("SELECT * FROM suborder_projection WHERE suborder_id = ?", (rs, i) -> new SuborderRef(
                rs.getObject("suborder_id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getObject("vendor_id", UUID.class),
                rs.getString("status"), rs.getBigDecimal("total"), rs.getString("currency"),
                read(rs.getString("item_summary")), ConversationRepository.instant(rs, "placed_at")), suborderId)
                .stream().findFirst();
    }

    public List<UUID> suborderIdsOfOrder(UUID orderId) {
        return jdbc.queryForList("SELECT suborder_id FROM suborder_projection WHERE order_id = ?", UUID.class, orderId);
    }

    private JsonNode read(String s) {
        try {
            return s == null ? null : json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(JsonNode n) {
        try {
            return n == null ? "[]" : json.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
