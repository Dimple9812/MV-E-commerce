package com.marketflow.inventory.messaging;

import com.marketflow.inventory.application.InventoryEvents;
import com.marketflow.inventory.application.ReservationService;
import com.marketflow.platform.kafka.EventListenerSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class InventoryConsumers {

    private final EventListenerSupport support;
    private final ReservationService reservations;
    private final JdbcTemplate jdbc;

    public InventoryConsumers(EventListenerSupport support, ReservationService reservations, JdbcTemplate jdbc) {
        this.support = support;
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    /**
     * Saga commands. These rely on business-key idempotency (one reservation row per order) instead of the generic
     * inbox, because a failed reservation must commit its FAILED outcome in a separate transaction from the
     * rolled-back reservation attempt.
     */
    @KafkaListener(topics = InventoryEvents.COMMANDS, groupId = "inventory-service.commands")
    public void onCommand(String message) {
        support.handle(message, event -> {
            support.codec().requireSupportedVersion(event, 1);
            InventoryEvents.ReservationCommand cmd = support.payload(event, InventoryEvents.ReservationCommand.class);
            String causation = event.eventId().toString();
            switch (event.eventType()) {
                case "InventoryReservationRequested" -> reservations.reserve(cmd.orderId(), cmd.lines(), causation);
                case "InventoryConfirmationRequested" -> reservations.confirm(cmd.orderId(), causation);
                case "InventoryReleaseRequested" -> reservations.release(cmd.orderId(), cmd.reason(), causation);
                case "InventoryRestockRequested" -> reservations.restock(cmd.orderId(), cmd.reason(), causation);
                default -> {
                    // unknown command types from newer producers are ignored, not dead-lettered
                }
            }
        });
    }

    public record VariantPayload(UUID variantId, String sku, String name, boolean active) {
    }

    public record ProductPayload(UUID productId, UUID vendorId, String name, String status, long productVersion,
                                 List<VariantPayload> variants) {
    }

    /** Creates an inventory item (stock 0) for every variant as soon as it exists in the catalog. */
    @KafkaListener(topics = "catalog.product.events", groupId = "inventory-service.products")
    public void onProduct(String message) {
        support.handleOnce(message, "inventory-service.products", Set.of("ProductCreated", "ProductUpdated",
                "ProductPublished", "ProductPriceChanged", "ProductArchived", "ProductBlocked", "ProductDeleted"), 1,
                event -> {
                    ProductPayload p = support.payload(event, ProductPayload.class);
                    if (p.variants() == null) {
                        return;
                    }
                    for (VariantPayload v : p.variants()) {
                        jdbc.update("""
                                INSERT INTO inventory_item (id, variant_id, product_id, vendor_id, sku, product_name,
                                                            variant_name, product_version)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT (variant_id) DO UPDATE
                                   SET sku = EXCLUDED.sku, product_name = EXCLUDED.product_name,
                                       variant_name = EXCLUDED.variant_name, product_version = EXCLUDED.product_version,
                                       updated_at = now()
                                 WHERE inventory_item.product_version < EXCLUDED.product_version
                                """, UUID.randomUUID(), v.variantId(), p.productId(), p.vendorId(), v.sku(), p.name(),
                                v.name(), p.productVersion());
                    }
                });
    }

    public record VendorPayload(UUID vendorId, UUID ownerUserId, String status, long vendorVersion) {
    }

    @KafkaListener(topics = "vendor.events", groupId = "inventory-service.vendors")
    public void onVendor(String message) {
        support.handleOnce(message, "inventory-service.vendors", Set.of("VendorApplicationSubmitted",
                "VendorApproved", "VendorRejected", "VendorSuspended", "VendorReinstated", "VendorProfileUpdated",
                "VendorCommissionChanged"), 1, event -> {
            VendorPayload v = support.payload(event, VendorPayload.class);
            jdbc.update("""
                    INSERT INTO vendor_projection (vendor_id, owner_user_id, status, vendor_version)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (vendor_id) DO UPDATE SET status = EXCLUDED.status,
                        vendor_version = EXCLUDED.vendor_version, updated_at = now()
                     WHERE vendor_projection.vendor_version < EXCLUDED.vendor_version
                    """, v.vendorId(), v.ownerUserId(), v.status(), v.vendorVersion());
        });
    }
}
