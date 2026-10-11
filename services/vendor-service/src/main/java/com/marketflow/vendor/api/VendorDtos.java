package com.marketflow.vendor.api;

import com.marketflow.vendor.application.EarningsService;
import com.marketflow.vendor.domain.EarningsEntry;
import com.marketflow.vendor.domain.Payout;
import com.marketflow.vendor.domain.StatusHistory;
import com.marketflow.vendor.domain.Vendor;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class VendorDtos {

    private VendorDtos() {
    }

    public record ApplicationRequest(
            @NotBlank @Size(min = 2, max = 120) String businessName,
            @Size(max = 2000) String description,
            @NotBlank @Email @Size(max = 254) String contactEmail,
            @Size(max = 30) @Pattern(regexp = "^[0-9+()\\-\\s]*$", message = "invalid phone") String contactPhone,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be ISO-3166 alpha-2") String country) {
    }

    public record DecisionRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ApproveRequest(@DecimalMin("0.0") @DecimalMax("0.5") @Digits(integer = 1, fraction = 4)
                                 BigDecimal commissionRate) {
    }

    public record CommissionRequest(@NotNull @DecimalMin("0.0") @DecimalMax("0.5") @Digits(integer = 1, fraction = 4)
                                    BigDecimal commissionRate) {
    }

    public record PayoutRequest(@NotNull UUID vendorId,
                                @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                @NotBlank @Size(max = 100) String reference) {
    }

    /** Full view for the owner and admins. */
    public record VendorView(UUID id, UUID ownerUserId, String businessName, String slug, String description,
                             String contactEmail, String contactPhone, String country, String status,
                             BigDecimal commissionRate, String statusReason, Instant submittedAt, Instant decidedAt) {
        public static VendorView of(Vendor v) {
            return new VendorView(v.getId(), v.getOwnerUserId(), v.getBusinessName(), v.getSlug(), v.getDescription(),
                    v.getContactEmail(), v.getContactPhone(), v.getCountry(), v.getStatus().name(),
                    v.getCommissionRate(), v.getStatusReason(), v.getSubmittedAt(), v.getDecidedAt());
        }
    }

    /** Storefront view: no contact data, commission or internal status reasons. */
    public record PublicVendorView(UUID id, String businessName, String slug, String description, String country,
                                   Instant memberSince) {
        public static PublicVendorView of(Vendor v) {
            return new PublicVendorView(v.getId(), v.getBusinessName(), v.getSlug(), v.getDescription(),
                    v.getCountry(), v.getCreatedAt());
        }
    }

    public record HistoryView(String fromStatus, String toStatus, UUID actorId, String reason, Instant occurredAt) {
        public static HistoryView of(StatusHistory h) {
            return new HistoryView(h.getFromStatus(), h.getToStatus(), h.getActorId(), h.getReason(),
                    h.getOccurredAt());
        }
    }

    public record EarningsEntryView(UUID id, UUID orderId, UUID suborderId, String type, BigDecimal grossAmount,
                                    BigDecimal commission, BigDecimal netAmount, String currency, Instant occurredAt) {
        public static EarningsEntryView of(EarningsEntry e) {
            return new EarningsEntryView(e.getId(), e.getOrderId(), e.getSuborderId(), e.getType().name(),
                    e.getGrossAmount(), e.getCommission(), e.getNetAmount(), e.getCurrency(), e.getOccurredAt());
        }
    }

    public record PayoutView(UUID id, UUID vendorId, BigDecimal amount, String currency, String reference,
                             String status, Instant createdAt) {
        public static PayoutView of(Payout p) {
            return new PayoutView(p.getId(), p.getVendorId(), p.getAmount(), p.getCurrency(), p.getReference(),
                    p.getStatus(), p.getCreatedAt());
        }
    }

    public record DashboardView(VendorView vendor, EarningsService.Summary earnings) {
    }
}
