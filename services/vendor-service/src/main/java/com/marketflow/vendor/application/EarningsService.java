package com.marketflow.vendor.application;

import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.web.ApiException;
import com.marketflow.vendor.domain.EarningsEntry;
import com.marketflow.vendor.domain.Payout;
import com.marketflow.vendor.domain.Vendor;
import com.marketflow.vendor.persistence.EarningsRepository;
import com.marketflow.vendor.persistence.PayoutRepository;
import com.marketflow.vendor.persistence.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EarningsService {

    private static final Logger log = LoggerFactory.getLogger(EarningsService.class);

    private final EarningsRepository earnings;
    private final PayoutRepository payouts;
    private final VendorRepository vendors;
    private final AuditRecorder audit;

    public EarningsService(EarningsRepository earnings, PayoutRepository payouts, VendorRepository vendors,
                           AuditRecorder audit) {
        this.earnings = earnings;
        this.payouts = payouts;
        this.vendors = vendors;
        this.audit = audit;
    }

    /** Suborder line as published by order-service in OrderConfirmed / OrderCancelled. */
    public record SuborderAmounts(UUID suborderId, UUID vendorId, BigDecimal itemsSubtotal, BigDecimal discountAmount,
                                  BigDecimal shippingAmount, BigDecimal commissionAmount, BigDecimal vendorNetAmount) {
    }

    /**
     * Records one ledger line per suborder. Runs inside the consumer's inbox transaction; the unique constraint
     * on (suborder_id, entry_type) is a second line of defence against duplicates.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID orderId, String currency, Instant at, List<SuborderAmounts> suborders,
                       EarningsEntry.Type type) {
        for (SuborderAmounts s : suborders) {
            if (!vendors.existsById(s.vendorId())) {
                log.warn("Earnings for unknown vendor {} skipped (order {})", s.vendorId(), orderId);
                continue;
            }
            if (earnings.existsBySuborderIdAndType(s.suborderId(), type)) {
                continue;
            }
            BigDecimal gross = s.itemsSubtotal().subtract(s.discountAmount()).add(s.shippingAmount());
            BigDecimal sign = type == EarningsEntry.Type.SALE ? BigDecimal.ONE : BigDecimal.ONE.negate();
            earnings.save(new EarningsEntry(s.vendorId(), orderId, s.suborderId(), type, gross.multiply(sign),
                    s.commissionAmount().multiply(sign), s.vendorNetAmount().multiply(sign), currency, at));
        }
    }

    @Transactional(readOnly = true)
    public Page<EarningsEntry> entries(UUID vendorId, Pageable pageable) {
        return earnings.findByVendorIdOrderByOccurredAtDesc(vendorId, pageable);
    }

    public record Summary(BigDecimal grossSales, BigDecimal commission, BigDecimal netEarnings, BigDecimal paidOut,
                          BigDecimal balance, long salesCount, long refundCount, BigDecimal commissionRate) {
    }

    @Transactional(readOnly = true)
    public Summary summary(Vendor vendor) {
        EarningsRepository.Totals t = earnings.totals(vendor.getId());
        BigDecimal paid = payouts.totalPaid(vendor.getId());
        return new Summary(t.getGross(), t.getCommission(), t.getNet(), paid, t.getNet().subtract(paid),
                t.getSales(), t.getRefunds(), vendor.getCommissionRate());
    }

    @Transactional(readOnly = true)
    public List<Payout> payouts(UUID vendorId) {
        return payouts.findByVendorIdOrderByCreatedAtDesc(vendorId);
    }

    @Transactional
    public Payout recordPayout(UUID adminId, UUID vendorId, BigDecimal amount, String reference) {
        Vendor v = vendors.findForUpdate(vendorId).orElseThrow(() -> ApiException.notFound("Vendor"));
        Summary s = summary(v);
        if (amount.compareTo(s.balance()) > 0) {
            throw ApiException.unprocessable("insufficient_balance",
                    "Payout exceeds the vendor's outstanding balance of " + s.balance());
        }
        Payout p = payouts.save(new Payout(vendorId, amount, "USD", reference, adminId));
        audit.record(adminId, "ADMIN", "vendor.payout_recorded", "Vendor", vendorId.toString(),
                Map.of("amount", amount.toPlainString(), "reference", reference));
        return p;
    }
}
