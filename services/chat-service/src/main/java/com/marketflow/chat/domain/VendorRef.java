package com.marketflow.chat.domain;

import java.util.UUID;

/** The part of the vendor projection that decisions depend on. */
public record VendorRef(UUID vendorId, UUID ownerUserId, String businessName, String slug, String status) {

    public boolean approved() {
        return "APPROVED".equals(status);
    }
}
