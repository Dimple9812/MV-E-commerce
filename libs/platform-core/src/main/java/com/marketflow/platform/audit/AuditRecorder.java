package com.marketflow.platform.audit;

import com.marketflow.platform.outbox.OutboxWriter;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Records sensitive operations (approvals, role changes, refunds, stock adjustments...) as {@code AuditRecorded}
 * events through the outbox, in the same transaction as the change itself. The analytics service builds the
 * platform-wide audit log from them. Details must never contain secrets, tokens or full personal data.
 */
public class AuditRecorder {

    public static final String TOPIC = "platform.audit.events";

    private final OutboxWriter outbox;
    private final String service;

    public AuditRecorder(OutboxWriter outbox, String service) {
        this.outbox = outbox;
        this.service = service;
    }

    public record AuditPayload(String service, UUID actorId, String actorRole, String action, String resourceType,
                               String resourceId, String outcome, Map<String, Object> details) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String actorRole, String action, String resourceType, String resourceId,
                       Map<String, Object> details) {
        record(actorId, actorRole, action, resourceType, resourceId, "SUCCESS", details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String actorRole, String action, String resourceType, String resourceId,
                       String outcome, Map<String, Object> details) {
        outbox.append(TOPIC, "AuditRecorded", 1, resourceType, resourceId,
                new AuditPayload(service, actorId, actorRole, action, resourceType, resourceId, outcome,
                        details == null ? Map.of() : details));
    }
}
