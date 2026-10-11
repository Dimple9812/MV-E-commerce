package com.marketflow.order.domain;

/**
 * Customer-facing order status (derived from saga + fulfilment). The saga state is the internal workflow detail;
 * this is what customers, vendors and analytics see.
 */
public enum OrderStatus {
    PENDING, CONFIRMED, PARTIALLY_SHIPPED, SHIPPED, DELIVERED, CANCELLATION_REQUESTED, CANCELLED, REFUND_FAILED, FAILED
}
