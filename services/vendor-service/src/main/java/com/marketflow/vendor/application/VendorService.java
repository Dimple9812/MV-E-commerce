package com.marketflow.vendor.application;

import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.web.ApiException;
import com.marketflow.vendor.domain.StatusHistory;
import com.marketflow.vendor.domain.Vendor;
import com.marketflow.vendor.persistence.StatusHistoryRepository;
import com.marketflow.vendor.persistence.VendorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class VendorService {

    private final VendorRepository vendors;
    private final StatusHistoryRepository history;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;

    public VendorService(VendorRepository vendors, StatusHistoryRepository history, OutboxWriter outbox,
                         AuditRecorder audit) {
        this.vendors = vendors;
        this.history = history;
        this.outbox = outbox;
        this.audit = audit;
    }

    public record ApplicationData(String businessName, String description, String contactEmail, String contactPhone,
                                  String country) {
    }

    /**
     * Submits (or, after a rejection, re-submits) a seller application for the CALLING user. The owner is always
     * the authenticated user; there is no way to apply on someone else's behalf.
     */
    @Transactional
    public Vendor apply(UUID userId, ApplicationData data) {
        Vendor existing = vendors.findByOwnerUserId(userId).orElse(null);
        Vendor vendor;
        String eventType = "VendorApplicationSubmitted";
        if (existing == null) {
            vendor = vendors.save(Vendor.apply(userId, data.businessName(), uniqueSlug(data.businessName()),
                    data.description(), data.contactEmail(), data.contactPhone(), data.country()));
            history.save(new StatusHistory(vendor.getId(), null, Vendor.Status.PENDING, userId, "application"));
        } else if (existing.getStatus() == Vendor.Status.REJECTED) {
            vendor = existing;
            vendor.updateDetails(data.businessName(), data.description(), data.contactEmail(), data.contactPhone(),
                    data.country());
            Vendor.Status from = vendor.transition(Vendor.Status.PENDING, userId, "re-application");
            history.save(new StatusHistory(vendor.getId(), from, Vendor.Status.PENDING, userId, "re-application"));
        } else {
            throw ApiException.conflict("application_exists",
                    "You already have a vendor account with status " + existing.getStatus());
        }
        vendors.flush();
        publish(vendor, eventType, null);
        audit.record(userId, "CUSTOMER", "vendor.application_submitted", "Vendor", vendor.getId().toString(),
                Map.of("businessName", vendor.getBusinessName()));
        return vendor;
    }

    @Transactional(readOnly = true)
    public Vendor mine(UUID userId) {
        return vendors.findByOwnerUserId(userId).orElseThrow(() -> ApiException.notFound("Vendor account"));
    }

    @Transactional(readOnly = true)
    public Vendor approvedVendorOf(UUID userId) {
        Vendor v = mine(userId);
        if (!v.isApproved()) {
            throw ApiException.forbidden("Your vendor account is " + v.getStatus());
        }
        return v;
    }

    @Transactional(readOnly = true)
    public Vendor publicProfile(UUID vendorId) {
        return vendors.findById(vendorId).filter(Vendor::isApproved)
                .orElseThrow(() -> ApiException.notFound("Vendor"));
    }

    @Transactional
    public Vendor updateProfile(UUID userId, ApplicationData data) {
        Vendor v = vendors.findByOwnerUserId(userId).orElseThrow(() -> ApiException.notFound("Vendor account"));
        if (!v.isApproved()) {
            throw ApiException.forbidden("Only approved vendors can edit their storefront profile");
        }
        v.updateDetails(data.businessName(), data.description(), data.contactEmail(), data.contactPhone(),
                data.country());
        vendors.flush();
        publish(v, "VendorProfileUpdated", null);
        return v;
    }

    // ---------- admin workflow ----------

    @Transactional(readOnly = true)
    public Page<Vendor> list(Vendor.Status status, Pageable pageable) {
        return status == null ? vendors.findAll(pageable) : vendors.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public Vendor get(UUID vendorId) {
        return vendors.findById(vendorId).orElseThrow(() -> ApiException.notFound("Vendor"));
    }

    @Transactional(readOnly = true)
    public List<StatusHistory> history(UUID vendorId) {
        get(vendorId);
        return history.findByVendorIdOrderByOccurredAtAsc(vendorId);
    }

    @Transactional
    public Vendor approve(UUID adminId, UUID vendorId, BigDecimal commissionRate) {
        Vendor v = vendors.findForUpdate(vendorId).orElseThrow(() -> ApiException.notFound("Vendor"));
        if (commissionRate != null) {
            v.changeCommission(commissionRate);
        }
        return move(adminId, v, Vendor.Status.APPROVED, "approved", "VendorApproved");
    }

    @Transactional
    public Vendor reject(UUID adminId, UUID vendorId, String reason) {
        Vendor v = vendors.findForUpdate(vendorId).orElseThrow(() -> ApiException.notFound("Vendor"));
        return move(adminId, v, Vendor.Status.REJECTED, reason, "VendorRejected");
    }

    @Transactional
    public Vendor suspend(UUID adminId, UUID vendorId, String reason) {
        Vendor v = vendors.findForUpdate(vendorId).orElseThrow(() -> ApiException.notFound("Vendor"));
        return move(adminId, v, Vendor.Status.SUSPENDED, reason, "VendorSuspended");
    }

    @Transactional
    public Vendor reinstate(UUID adminId, UUID vendorId) {
        Vendor v = vendors.findForUpdate(vendorId).orElseThrow(() -> ApiException.notFound("Vendor"));
        if (v.getStatus() != Vendor.Status.SUSPENDED) {
            throw ApiException.conflict("illegal_vendor_transition", "Only suspended vendors can be reinstated");
        }
        return move(adminId, v, Vendor.Status.APPROVED, "reinstated", "VendorReinstated");
    }

    @Transactional
    public Vendor changeCommission(UUID adminId, UUID vendorId, BigDecimal rate) {
        Vendor v = vendors.findForUpdate(vendorId).orElseThrow(() -> ApiException.notFound("Vendor"));
        BigDecimal old = v.getCommissionRate();
        v.changeCommission(rate);
        vendors.flush();
        publish(v, "VendorCommissionChanged", null);
        audit.record(adminId, "ADMIN", "vendor.commission_changed", "Vendor", vendorId.toString(),
                Map.of("from", old.toPlainString(), "to", v.getCommissionRate().toPlainString()));
        return v;
    }

    private Vendor move(UUID adminId, Vendor v, Vendor.Status to, String reason, String eventType) {
        Vendor.Status from = v.transition(to, adminId, reason);
        history.save(new StatusHistory(v.getId(), from, to, adminId, reason));
        vendors.flush(); // bump @Version so the event carries the committed version
        publish(v, eventType, reason);
        audit.record(adminId, "ADMIN", "vendor." + to.name().toLowerCase(Locale.ROOT), "Vendor", v.getId().toString(),
                Map.of("from", from.name(), "to", to.name()));
        return v;
    }

    /** Called after flush(), so getVersion() is the version this transaction will commit. */
    private void publish(Vendor v, String eventType, String reason) {
        outbox.append(VendorEvents.TOPIC, eventType, 1, VendorEvents.AGGREGATE, v.getId().toString(),
                VendorEvents.VendorSnapshot.of(v, reason, v.getVersion()));
    }

    String uniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "vendor";
        }
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        String slug = base;
        int i = 2;
        while (vendors.existsBySlug(slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }
}
