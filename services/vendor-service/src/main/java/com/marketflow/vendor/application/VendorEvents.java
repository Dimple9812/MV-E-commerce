package com.marketflow.vendor.application;

import com.marketflow.vendor.domain.Vendor;

import java.math.BigDecimal;
import java.util.UUID;

/** Payloads published on {@code vendor.events}. Contract: contracts/events/vendor.events.schema.json */
public final class VendorEvents {

    public static final String TOPIC = "vendor.events";
    public static final String AGGREGATE = "Vendor";

    private VendorEvents() {
    }

    /**
     * One payload shape for every vendor event: consumers (identity, catalog, inventory, order, notification,
     * analytics) keep projections of exactly these fields. {@code vendorVersion} lets them discard stale events.
     */
    public record VendorSnapshot(UUID vendorId, UUID ownerUserId, String businessName, String slug, String status,
                                 BigDecimal commissionRate, String contactEmail, String reason, long vendorVersion) {

        public static VendorSnapshot of(Vendor v, String reason, long version) {
            return new VendorSnapshot(v.getId(), v.getOwnerUserId(), v.getBusinessName(), v.getSlug(),
                    v.getStatus().name(), v.getCommissionRate(), v.getContactEmail(), reason, version);
        }
    }
}
