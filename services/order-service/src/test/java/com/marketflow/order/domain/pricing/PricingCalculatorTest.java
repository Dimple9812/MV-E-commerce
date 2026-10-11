package com.marketflow.order.domain.pricing;

import com.marketflow.platform.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingCalculatorTest {

    private final PricingPolicy policy = new PricingPolicy(new BigDecimal("0.08"), new BigDecimal("4.99"),
            new BigDecimal("50.00"), Map.of("WELCOME10", new BigDecimal("0.10"), "THIRD", new BigDecimal("0.3333")));
    private final PricingCalculator calc = new PricingCalculator(policy);

    private static final UUID V1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID V2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID V3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private PricingCalculator.Line line(UUID vendor, String sku, String price, int qty, String commission) {
        return new PricingCalculator.Line(UUID.randomUUID(), UUID.randomUUID(), vendor, "Vendor " + vendor,
                new BigDecimal(commission), sku, "Product " + sku, "Default", null, new BigDecimal(price), qty);
    }

    @Test
    void singleVendorWithoutCouponBelowFreeShipping() {
        var q = calc.price(List.of(line(V1, "A", "10.00", 2, "0.10")), null);
        assertThat(q.suborders()).hasSize(1);
        var s = q.suborders().get(0);
        assertThat(s.itemsSubtotal()).isEqualByComparingTo("20.00");
        assertThat(s.shipping()).isEqualByComparingTo("4.99");
        assertThat(s.tax()).isEqualByComparingTo("1.60");
        assertThat(s.total()).isEqualByComparingTo("26.59");
        assertThat(s.commission()).isEqualByComparingTo("2.00");
        assertThat(s.vendorNet()).isEqualByComparingTo("22.99");
        assertThat(q.grandTotal()).isEqualByComparingTo("26.59");
        assertThat(q.couponCode()).isNull();
    }

    @Test
    void multiVendorCheckoutCreatesOneSuborderPerVendorWithFreeShippingAboveThreshold() {
        var q = calc.price(List.of(
                line(V1, "A", "30.00", 2, "0.10"),   // 60.00 -> free shipping
                line(V2, "B", "15.00", 1, "0.15"),   // 15.00 -> 4.99
                line(V3, "C", "9.99", 3, "0.05"),    // 29.97 -> 4.99
                line(V1, "D", "5.00", 1, "0.10")), null); // joins V1 -> 65.00
        assertThat(q.suborders()).hasSize(3);
        var byVendor = q.suborders().stream().collect(java.util.stream.Collectors.toMap(
                PricingCalculator.PricedSuborder::vendorId, s -> s));
        assertThat(byVendor.get(V1).itemsSubtotal()).isEqualByComparingTo("65.00");
        assertThat(byVendor.get(V1).items()).hasSize(2);
        assertThat(byVendor.get(V1).shipping()).isEqualByComparingTo("0.00");
        assertThat(byVendor.get(V2).shipping()).isEqualByComparingTo("4.99");
        assertThat(byVendor.get(V2).commission()).isEqualByComparingTo("2.25");
        assertThat(q.itemsSubtotal()).isEqualByComparingTo("109.97");
        assertThat(q.shippingTotal()).isEqualByComparingTo("9.98");
        BigDecimal sumTotals = q.suborders().stream().map(PricingCalculator.PricedSuborder::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(q.grandTotal()).isEqualByComparingTo(sumTotals);
        assertThat(q.grandTotal()).isEqualByComparingTo(q.itemsSubtotal().subtract(q.discountTotal())
                .add(q.shippingTotal()).add(q.taxTotal()));
    }

    @Test
    void couponDiscountIsAllocatedExactlyToTheCent() {
        var q = calc.price(List.of(
                line(V1, "A", "10.01", 1, "0.10"),
                line(V2, "B", "10.01", 1, "0.10"),
                line(V3, "C", "10.01", 1, "0.10")), "third");
        assertThat(q.couponCode()).isEqualTo("THIRD");
        // 30.03 * 0.3333 = 10.009... -> 10.01 total discount
        assertThat(q.discountTotal()).isEqualByComparingTo("10.01");
        BigDecimal allocated = q.suborders().stream().map(PricingCalculator.PricedSuborder::discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocated).isEqualByComparingTo(q.discountTotal());
        for (var s : q.suborders()) {
            BigDecimal itemDiscounts = s.items().stream().map(PricingCalculator.PricedItem::discount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(itemDiscounts).isEqualByComparingTo(s.discount());
        }
    }

    @Test
    void discountMayRemoveFreeShippingEligibility() {
        var q = calc.price(List.of(line(V1, "A", "52.00", 1, "0.10")), "WELCOME10");
        assertThat(q.discountTotal()).isEqualByComparingTo("5.20");
        assertThat(q.suborders().get(0).shipping()).isEqualByComparingTo("4.99"); // 46.80 < 50
        assertThat(q.suborders().get(0).commission()).isEqualByComparingTo("4.68");
    }

    @Test
    void unknownCouponIsRejected() {
        assertThatThrownBy(() -> calc.price(List.of(line(V1, "A", "1.00", 1, "0.1")), "FREEMONEY"))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @CsvSource({"1.00,1|1|1", "0.01,1|1|1", "100.00,1|2|3|4", "0.05,7|0|3"})
    void allocationAlwaysSumsToTotal(String total, String weights) {
        List<BigDecimal> w = Arrays.stream(weights.split("\\|")).map(BigDecimal::new).toList();
        List<BigDecimal> parts = PricingCalculator.allocate(new BigDecimal(total), w);
        assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
        assertThat(parts).allMatch(p -> p.signum() >= 0);
    }

    @Test
    void zeroWeightGetsNothing() {
        List<BigDecimal> parts = PricingCalculator.allocate(new BigDecimal("1.00"),
                List.of(new BigDecimal("0"), new BigDecimal("5")));
        assertThat(parts.get(0)).isEqualByComparingTo("0.00");
        assertThat(parts.get(1)).isEqualByComparingTo("1.00");
    }
}
