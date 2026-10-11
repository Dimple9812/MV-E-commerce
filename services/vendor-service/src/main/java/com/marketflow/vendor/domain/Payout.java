package com.marketflow.vendor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payout recorded by an administrator after money was transferred outside the platform (no real banking
 * integration exists in this project; see docs/implementation-status.md).
 */
@Entity
@Table(name = "payout")
public class Payout {

    @Id
    private UUID id;
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false)
    private String reference;
    @Column(nullable = false)
    private String status = "PAID";
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payout() {
    }

    public Payout(UUID vendorId, BigDecimal amount, String currency, String reference, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.vendorId = vendorId;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReference() {
        return reference;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
