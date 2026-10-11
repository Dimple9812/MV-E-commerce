package com.marketflow.order.config;

import com.marketflow.order.domain.pricing.PricingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "marketflow.order")
public record OrderProperties(Saga saga, Pricing pricing, int maxLinesPerOrder) {

    /**
     * Per-step timeouts. On timeout the step is retried (commands are idempotent) up to maxAttempts; what happens
     * next depends on the step (see CheckoutSagaOrchestrator#onTimeout).
     */
    public record Saga(Duration inventoryTimeout, Duration paymentTimeout, Duration captureTimeout,
                       Duration confirmTimeout, Duration compensationTimeout, Duration reconciliationInterval,
                       int maxAttempts, int maxReconciliationAttempts, Duration scanInterval) {
    }

    public record Pricing(BigDecimal taxRate, BigDecimal flatShipping, BigDecimal freeShippingThreshold,
                          Map<String, BigDecimal> coupons) {
        public PricingPolicy toPolicy() {
            return new PricingPolicy(taxRate, flatShipping, freeShippingThreshold, coupons == null ? Map.of() : coupons);
        }
    }
}
