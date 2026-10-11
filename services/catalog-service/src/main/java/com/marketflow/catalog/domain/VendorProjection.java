package com.marketflow.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Local, eventually consistent copy of vendor-service data (fed by vendor.events). Never written via the API. */
@Entity
@Table(name = "vendor_projection")
public class VendorProjection {

    @Id
    @Column(name = "vendor_id")
    private UUID vendorId;
    @Column(name = "owner_user_id", nullable = false, unique = true)
    private UUID ownerUserId;
    @Column(name = "business_name", nullable = false)
    private String businessName;
    @Column(nullable = false)
    private String slug;
    @Column(nullable = false)
    private String status;
    @Column(name = "vendor_version", nullable = false)
    private long vendorVersion;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VendorProjection() {
    }

    public VendorProjection(UUID vendorId, UUID ownerUserId) {
        this.vendorId = vendorId;
        this.ownerUserId = ownerUserId;
        this.vendorVersion = -1;
    }

    /** @return false if the incoming event is older than what we already applied */
    public boolean apply(String businessName, String slug, String status, long version) {
        if (version <= vendorVersion) {
            return false;
        }
        this.businessName = businessName;
        this.slug = slug;
        this.status = status;
        this.vendorVersion = version;
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getSlug() {
        return slug;
    }

    public String getStatus() {
        return status;
    }
}
