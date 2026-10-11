package com.marketflow.order.domain.pricing;

import com.marketflow.platform.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes authoritative order amounts on the server. Pure function: no I/O, fully unit-tested.
 *
 * <p>Rules (all amounts BigDecimal, scale 2, HALF_EVEN):
 * <ol>
 *   <li>Items are grouped into one suborder per vendor; {@code itemsSubtotal = Σ unitPrice × qty}.</li>
 *   <li>A percentage coupon is applied to the whole order and allocated to suborders, then to items, with the
 *       largest-remainder method so the parts always add up to the whole to the cent.</li>
 *   <li>Shipping is per suborder: flat fee unless the discounted suborder subtotal reaches the free threshold.</li>
 *   <li>Tax = rate × (subtotal − discount) per suborder; shipping is not taxed.</li>
 *   <li>Commission = vendor rate × (subtotal − discount). Vendor net = subtotal − discount + shipping − commission
 *       (tax is collected and remitted by the platform).</li>
 * </ol>
 */
public final class PricingCalculator {

    private final PricingPolicy policy;

    public PricingCalculator(PricingPolicy policy) {
        this.policy = policy;
    }

    public record Line(UUID productId, UUID variantId, UUID vendorId, String vendorName, BigDecimal commissionRate,
                       String sku, String productName, String variantName, String imageUrl, BigDecimal unitPrice,
                       int quantity) {
    }

    public record PricedItem(Line line, BigDecimal lineSubtotal, BigDecimal discount, BigDecimal lineTotal) {
    }

    public record PricedSuborder(UUID vendorId, String vendorName, BigDecimal commissionRate, List<PricedItem> items,
                                 BigDecimal itemsSubtotal, BigDecimal discount, BigDecimal shipping, BigDecimal tax,
                                 BigDecimal total, BigDecimal commission, BigDecimal vendorNet) {
    }

    public record Quote(List<PricedSuborder> suborders, BigDecimal itemsSubtotal, BigDecimal discountTotal,
                        BigDecimal shippingTotal, BigDecimal taxTotal, BigDecimal grandTotal, String couponCode) {
    }

    public Quote price(List<Line> lines, String couponCode) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("No lines to price");
        }
        BigDecimal couponRate = policy.couponRate(couponCode);

        Map<UUID, List<Line>> byVendor = new LinkedHashMap<>();
        lines.stream().sorted(Comparator.comparing(Line::vendorId).thenComparing(Line::sku))
                .forEach(l -> byVendor.computeIfAbsent(l.vendorId(), k -> new ArrayList<>()).add(l));

        List<BigDecimal> vendorSubtotals = byVendor.values().stream()
                .map(ls -> ls.stream().map(l -> Money.normalize(l.unitPrice().multiply(BigDecimal.valueOf(l.quantity()))))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .toList();
        BigDecimal itemsSubtotal = vendorSubtotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discountTotal = Money.percentOf(itemsSubtotal, couponRate);
        List<BigDecimal> vendorDiscounts = allocate(discountTotal, vendorSubtotals);

        List<PricedSuborder> suborders = new ArrayList<>();
        int i = 0;
        for (List<Line> vendorLines : byVendor.values()) {
            BigDecimal subtotal = vendorSubtotals.get(i);
            BigDecimal discount = vendorDiscounts.get(i);
            i++;
            List<BigDecimal> lineSubtotals = vendorLines.stream()
                    .map(l -> Money.normalize(l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))).toList();
            List<BigDecimal> lineDiscounts = allocate(discount, lineSubtotals);
            List<PricedItem> items = new ArrayList<>();
            for (int j = 0; j < vendorLines.size(); j++) {
                items.add(new PricedItem(vendorLines.get(j), lineSubtotals.get(j), lineDiscounts.get(j),
                        lineSubtotals.get(j).subtract(lineDiscounts.get(j))));
            }
            BigDecimal net = subtotal.subtract(discount);
            BigDecimal shipping = net.compareTo(policy.freeShippingThreshold()) >= 0 ? Money.ZERO
                    : policy.flatShipping();
            BigDecimal tax = Money.percentOf(net, policy.taxRate());
            Line first = vendorLines.get(0);
            BigDecimal commission = Money.percentOf(net, first.commissionRate());
            BigDecimal total = net.add(shipping).add(tax);
            BigDecimal vendorNet = net.add(shipping).subtract(commission);
            suborders.add(new PricedSuborder(first.vendorId(), first.vendorName(), first.commissionRate(), items,
                    subtotal, discount, shipping, tax, total, commission, vendorNet));
        }
        BigDecimal shippingTotal = suborders.stream().map(PricedSuborder::shipping).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taxTotal = suborders.stream().map(PricedSuborder::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grand = suborders.stream().map(PricedSuborder::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Quote(suborders, Money.normalize(itemsSubtotal), Money.normalize(discountTotal),
                Money.normalize(shippingTotal), Money.normalize(taxTotal), Money.normalize(grand),
                couponRate.signum() > 0 ? couponCode.trim().toUpperCase(java.util.Locale.ROOT) : null);
    }

    /**
     * Splits {@code total} proportionally to {@code weights} so that the parts sum EXACTLY to total (cents),
     * giving leftover cents to the parts with the largest fractional remainders (ties: earlier part first).
     */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> out = new ArrayList<>();
        if (total.signum() == 0 || sum.signum() == 0) {
            weights.forEach(w -> out.add(Money.ZERO));
            return out;
        }
        long totalCents = total.movePointRight(2).longValueExact();
        long[] cents = new long[weights.size()];
        BigDecimal[] remainders = new BigDecimal[weights.size()];
        long assigned = 0;
        for (int k = 0; k < weights.size(); k++) {
            BigDecimal exact = BigDecimal.valueOf(totalCents).multiply(weights.get(k)).divide(sum, 10, RoundingMode.DOWN);
            cents[k] = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            remainders[k] = exact.subtract(BigDecimal.valueOf(cents[k]));
            assigned += cents[k];
        }
        long left = totalCents - assigned;
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < weights.size(); k++) {
            order.add(k);
        }
        order.sort((a, b) -> {
            int c = remainders[b].compareTo(remainders[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        for (int k = 0; k < left; k++) {
            cents[order.get(k % order.size())]++;
        }
        for (long c : cents) {
            out.add(BigDecimal.valueOf(c, 2));
        }
        return out;
    }
}
