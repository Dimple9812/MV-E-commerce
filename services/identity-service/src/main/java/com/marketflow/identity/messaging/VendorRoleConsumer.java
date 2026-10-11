package com.marketflow.identity.messaging;

import com.marketflow.identity.application.UserService;
import com.marketflow.platform.kafka.EventListenerSupport;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Grants / revokes the VENDOR role when vendor-service approves, suspends or reinstates a vendor. This is the
 * only path to the VENDOR role: nothing a user sends at sign-up can grant it.
 */
@Component
public class VendorRoleConsumer {

    static final String CONSUMER = "identity-service.vendor-role";
    private static final Set<String> TYPES = Set.of("VendorApproved", "VendorReinstated", "VendorSuspended");

    private final EventListenerSupport support;
    private final UserService users;

    public VendorRoleConsumer(EventListenerSupport support, UserService users) {
        this.support = support;
        this.users = users;
    }

    public record VendorStatusPayload(UUID vendorId, UUID ownerUserId, String status) {
    }

    @KafkaListener(topics = "vendor.events", groupId = CONSUMER)
    public void onVendorEvent(String message) {
        support.handleOnce(message, CONSUMER, TYPES, 1, event -> {
            VendorStatusPayload p = support.payload(event, VendorStatusPayload.class);
            boolean grant = !"VendorSuspended".equals(event.eventType());
            users.applyVendorRole(p.ownerUserId(), grant, event.eventType());
        });
    }
}
