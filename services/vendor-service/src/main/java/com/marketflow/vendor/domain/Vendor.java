package com.marketflow.vendor.domain;

import com.marketflow.platform.web.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "vendor")
public class Vendor {

    /**
     * Legal transitions of the approval workflow. Anything else is rejected with 409.
     * <pre>
     * PENDING  -> APPROVED | REJECTED
     * REJECTED -> PENDING (re-application)
     * APPROVED -> SUSPENDED
     * SUSPENDED -> APPROVED (reinstate)
     * </pre>
     */
    public enum Status {
        PENDING, APPROVED, REJECTED, SUSPENDED;

        private static final Map<Status, Set<Status>> ALLOWED = Map.of(
                PENDING, Set.of(APPROVED, REJECTED),
                REJECTED, Set.of(PENDING),
                APPROVED, Set.of(SUSPENDED),
                SUSPENDED, Set.of(APPROVED));

        public boolean canMoveTo(Status next) {
            return ALLOWED.getOrDefault(this, Set.of()).contains(next);
        }
    }

    public static final BigDecimal DEFAULT_COMMISSION = new BigDecimal("0.1000");
    public static final BigDecimal MAX_COMMISSION = new BigDecimal("0.5000");

    @Id
    private UUID id;
    @Column(name = "owner_user_id", nullable = false, unique = true)
    private UUID ownerUserId;
    @Column(name = "business_name", nullable = false)
    private String businessName;
    @Column(nullable = false, unique = true)
    private String slug;
    private String description;
    @Column(name = "contact_email", nullable = false)
    private String contactEmail;
    @Column(name = "contact_phone")
    private String contactPhone;
    @Column(nullable = false, length = 2)
    private String country;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;
    @Column(name = "status_reason")
    private String statusReason;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decided_by")
    private UUID decidedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private Long version; // null until first persist, so Spring Data treats assigned-ID entities as new

    protected Vendor() {
    }

    public static Vendor apply(UUID ownerUserId, String businessName, String slug, String description,
                               String contactEmail, String contactPhone, String country) {
        Vendor v = new Vendor();
        v.id = UUID.randomUUID();
        v.ownerUserId = ownerUserId;
        v.slug = slug;
        v.status = Status.PENDING;
        v.commissionRate = DEFAULT_COMMISSION;
        v.createdAt = Instant.now();
        v.updateDetails(businessName, description, contactEmail, contactPhone, country);
        v.submittedAt = v.createdAt;
        return v;
    }

    public void updateDetails(String businessName, String description, String contactEmail, String contactPhone,
                              String country) {
        this.businessName = businessName.trim();
        this.description = description;
        this.contactEmail = contactEmail.trim().toLowerCase(java.util.Locale.ROOT);
        this.contactPhone = contactPhone;
        this.country = country.toUpperCase(java.util.Locale.ROOT);
        this.updatedAt = Instant.now();
    }

    /** @return the previous status */
    public Status transition(Status next, UUID actor, String reason) {
        if (!status.canMoveTo(next)) {
            throw ApiException.conflict("illegal_vendor_transition",
                    "Vendor cannot move from " + status + " to " + next);
        }
        Status previous = status;
        status = next;
        statusReason = reason;
        Instant now = Instant.now();
        updatedAt = now;
        if (next == Status.PENDING) {
            submittedAt = now;
            decidedAt = null;
            decidedBy = null;
        } else {
            decidedAt = now;
            decidedBy = actor;
        }
        return previous;
    }

    public void changeCommission(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(MAX_COMMISSION) > 0) {
            throw ApiException.badRequest("invalid_commission", "Commission rate must be between 0 and 0.5");
        }
        this.commissionRate = rate.setScale(4, java.math.RoundingMode.HALF_EVEN);
        this.updatedAt = Instant.now();
    }

    public boolean isApproved() {
        return status == Status.APPROVED;
    }

    public UUID getId() {
        return id;
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

    public String getDescription() {
        return description;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getCountry() {
        return country;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
