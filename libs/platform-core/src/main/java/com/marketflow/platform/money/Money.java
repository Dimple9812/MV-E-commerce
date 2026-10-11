package com.marketflow.platform.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Monetary helpers. All amounts are BigDecimal with 2 decimal places, banker's rounding. Never use double. */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    private Money() {
    }

    public static BigDecimal of(String value) {
        return normalize(new BigDecimal(value));
    }

    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    /** amount × rate, where rate is a fraction such as 0.1250. */
    public static BigDecimal percentOf(BigDecimal amount, BigDecimal rate) {
        return normalize(amount.multiply(rate));
    }
}
