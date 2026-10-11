package com.marketflow.vendor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable ledger line. Refunds are recorded as negative amounts rather than by editing the sale. */
@Entity
@Table(name = "earnings_entry")
public class EarningsEntry {

    public enum Type { SALE, REFUND }

    @Id
    private UUID id;
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "suborder_id", nullable = false)
    private UUID suborderId;
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private Type type;
    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount;
    @Column(nullable = false)
    private BigDecimal commission;
    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected EarningsEntry() {
    }

    public EarningsEntry(UUID vendorId, UUID orderId, UUID suborderId, Type type, BigDecimal gross,
                         BigDecimal commission, BigDecimal net, String currency, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.vendorId = vendorId;
        this.orderId = orderId;
        this.suborderId = suborderId;
        this.type = type;
        this.grossAmount = gross;
        this.commission = commission;
        this.netAmount = net;
        this.currency = currency;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getSuborderId() {
        return suborderId;
    }

    public Type getType() {
        return type;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getCommission() {
        return commission;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
