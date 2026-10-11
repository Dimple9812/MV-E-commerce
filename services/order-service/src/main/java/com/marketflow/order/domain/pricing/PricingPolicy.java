package com.marketflow.order.domain.pricing;

import com.marketflow.platform.web.ApiException;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Configurable pricing parameters (see {@code marketflow.pricing.*}).
 *
 * @param coupons code → fraction (e.g. WELCOME10 → 0.10)
 */
public record PricingPolicy(BigDecimal taxRate, BigDecimal flatShipping, BigDecimal freeShippingThreshold,
                            Map<String, BigDecimal> coupons) {

    public BigDecimal couponRate(String code) {
        if (code == null || code.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = coupons == null ? null : coupons.get(code.trim().toUpperCase(Locale.ROOT));
        if (rate == null) {
            throw ApiException.unprocessable("invalid_coupon", "Coupon code is not valid");
        }
        return rate;
    }
}
