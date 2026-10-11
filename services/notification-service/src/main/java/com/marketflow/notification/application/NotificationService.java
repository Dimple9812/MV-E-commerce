package com.marketflow.notification.application;

import com.marketflow.notification.application.NotificationTemplates.Draft;
import com.marketflow.notification.delivery.EmailSender;
import com.marketflow.platform.events.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two phases per event:
 * <ol>
 *   <li><b>ingest</b> (one transaction): update contact projections and insert the notification rows; the unique
 *       dedupe key makes this idempotent.</li>
 *   <li><b>deliver</b>: send every still-PENDING e-mail of this event. A transient failure is thrown so the
 *       message goes to the next retry topic; on redelivery step 1 is a no-op and only unsent mails are retried.
 *       Already SENT mails are never sent again.</li>
 * </ol>
 * Nothing here runs inside the checkout: notifications can be slow or failing without affecting orders.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailSender email;
    private final MeterRegistry meters;

    public NotificationService(JdbcTemplate jdbc, TransactionTemplate tx, EmailSender email, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.email = email;
        this.meters = meters;
    }

    public void process(EventEnvelope e) {
        tx.executeWithoutResult(s -> ingest(e));
        deliver(e.eventId());
    }

    void ingest(EventEnvelope e) {
        var p = e.payload();
        switch (e.eventType()) {
            case "UserRegistered" -> jdbc.update("""
                    INSERT INTO user_contact (user_id, email, full_name) VALUES (?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET email = EXCLUDED.email, full_name = EXCLUDED.full_name,
                        updated_at = now()
                    """, UUID.fromString(p.path("userId").asText()), p.path("email").asText(),
                    p.path("fullName").asText());
            case "VendorApplicationSubmitted", "VendorApproved", "VendorRejected", "VendorSuspended",
                 "VendorReinstated", "VendorProfileUpdated" -> jdbc.update("""
                    INSERT INTO vendor_contact (vendor_id, owner_user_id, email, business_name, vendor_version)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (vendor_id) DO UPDATE SET email = EXCLUDED.email,
                        business_name = EXCLUDED.business_name, vendor_version = EXCLUDED.vendor_version,
                        updated_at = now()
                    WHERE vendor_contact.vendor_version < EXCLUDED.vendor_version
                    """, UUID.fromString(p.path("vendorId").asText()), UUID.fromString(p.path("ownerUserId").asText()),
                    p.path("contactEmail").asText(), p.path("businessName").asText(), p.path("vendorVersion").asLong());
            default -> {
            }
        }
        for (Draft d : NotificationTemplates.plan(e)) {
            UUID userId = d.userId();
            String address = null;
            if (d.audience() == NotificationTemplates.Audience.VENDOR) {
                List<Map<String, Object>> v = jdbc.queryForList(
                        "SELECT owner_user_id, email FROM vendor_contact WHERE vendor_id = ?", d.vendorId());
                if (!v.isEmpty()) {
                    userId = userId == null ? (UUID) v.get(0).get("owner_user_id") : userId;
                    address = (String) v.get(0).get("email");
                }
            } else if (userId != null) {
                address = jdbc.queryForList("SELECT email FROM user_contact WHERE user_id = ?", String.class, userId)
                        .stream().findFirst().orElse(null);
            }
            if (d.channel().equals("IN_APP") && userId == null) {
                continue; // recipient unknown (projection not built yet): nothing to show
            }
            if (d.channel().equals("EMAIL") && address == null) {
                // contact projection not there yet; retrying later (retry topic) gives it time to arrive
                throw new EmailSender.TransientDeliveryException("recipient contact not yet known", null);
            }
            jdbc.update("""
                    INSERT INTO notification (id, recipient_user_id, recipient_email, channel, template, subject, body,
                                              link, status, dedupe_key, source_event_id, sent_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'IN_APP' THEN now() END)
                    ON CONFLICT (dedupe_key) DO NOTHING
                    """, UUID.randomUUID(), userId, address, d.channel(), d.template(), d.subject(), d.body(), d.link(),
                    d.channel().equals("IN_APP") ? "SENT" : "PENDING", d.dedupeKey(), e.eventId(), d.channel());
        }
    }

    void deliver(UUID eventId) {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT id, recipient_email, subject, body, template FROM notification
                WHERE source_event_id = ? AND status = 'PENDING' AND channel = 'EMAIL'
                """, eventId);
        RuntimeException firstFailure = null;
        for (Map<String, Object> n : pending) {
            UUID id = (UUID) n.get("id");
            try {
                email.send((String) n.get("recipient_email"), (String) n.get("subject"), (String) n.get("body"));
                jdbc.update("UPDATE notification SET status = 'SENT', sent_at = now(), attempts = attempts + 1 "
                        + "WHERE id = ? AND status = 'PENDING'", id);
                jdbc.update("INSERT INTO delivery_attempt (notification_id, outcome) VALUES (?, 'SENT')", id);
                meters.counter("notifications.delivered", "template", (String) n.get("template")).increment();
            } catch (EmailSender.PermanentDeliveryException ex) {
                attempt(id, "PERMANENT_FAILURE", ex.getMessage());
                jdbc.update("UPDATE notification SET status = 'FAILED', last_error = ? WHERE id = ?", ex.getMessage(),
                        id);
                meters.counter("notifications.failed", "reason", "permanent").increment();
                firstFailure = firstFailure == null ? ex : firstFailure;
            } catch (RuntimeException ex) {
                attempt(id, "TRANSIENT_FAILURE", ex.getMessage());
                meters.counter("notifications.retries").increment();
                firstFailure = firstFailure == null ? new EmailSender.TransientDeliveryException(ex.getMessage(), ex)
                        : firstFailure;
            }
        }
        if (firstFailure != null) {
            throw firstFailure; // transient -> next retry topic; permanent -> DLT
        }
    }

    private void attempt(UUID id, String outcome, String error) {
        String e = error == null ? null : error.substring(0, Math.min(error.length(), 290));
        jdbc.update("INSERT INTO delivery_attempt (notification_id, outcome, error) VALUES (?, ?, ?)", id, outcome, e);
        jdbc.update("UPDATE notification SET attempts = attempts + 1, last_error = ? WHERE id = ?", e, id);
    }

    /** Called from the DLT handler: retries are exhausted, stop trying and make it visible to operators. */
    public void markExhausted(UUID eventId, String reason) {
        int n = jdbc.update("UPDATE notification SET status = 'FAILED', last_error = ? "
                + "WHERE source_event_id = ? AND status = 'PENDING'", "retries exhausted: " + reason, eventId);
        meters.counter("notifications.failed", "reason", "exhausted").increment(n);
        log.error("Notifications for event {} moved to FAILED after retries ({} rows)", eventId, n);
    }
}
