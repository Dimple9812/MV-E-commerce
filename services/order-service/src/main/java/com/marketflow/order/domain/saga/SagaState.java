package com.marketflow.order.domain.saga;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Checkout saga states. Legal transitions are declared here once; {@link CheckoutSaga#moveTo} rejects anything
 * else, so the workflow can be read (and reviewed) as a table rather than scattered boolean flags.
 *
 * <pre>
 * CREATED ─▶ INVENTORY_PENDING ─(InventoryReserved)─▶ INVENTORY_RESERVED ─▶ PAYMENT_PENDING
 * PAYMENT_PENDING ─(PaymentAuthorized)─▶ PAYMENT_AUTHORIZED ─(PaymentCaptured)─▶ INVENTORY_CONFIRM_PENDING
 * INVENTORY_CONFIRM_PENDING ─(InventoryReservationConfirmed)─▶ CONFIRMED
 *
 * INVENTORY_PENDING ─(ReservationFailed)─▶ CANCELLED                  nothing to undo (all-or-nothing reserve)
 * INVENTORY_PENDING ─(timeout after retries)─▶ COMPENSATING            release (tombstone) then CANCELLED
 * PAYMENT_PENDING ─(PaymentFailed)─▶ COMPENSATING                      release inventory
 * PAYMENT_PENDING ─(OutcomeUnknown | timeout)─▶ RECONCILIATION_REQUIRED never assume failure
 * RECONCILIATION_REQUIRED ─(Authorized)─▶ PAYMENT_AUTHORIZED | ─(Failed)─▶ COMPENSATING | ─(exhausted)─▶ FAILED
 * PAYMENT_AUTHORIZED ─(CaptureFailed)─▶ COMPENSATING                   void authorisation + release inventory
 * INVENTORY_CONFIRM_PENDING ─(ConfirmationFailed)─▶ COMPENSATING       refund payment
 * COMPENSATING ─(all compensations acknowledged)─▶ CANCELLED
 * </pre>
 */
public enum SagaState {
    CREATED, INVENTORY_PENDING, INVENTORY_RESERVED, PAYMENT_PENDING, PAYMENT_AUTHORIZED, INVENTORY_CONFIRM_PENDING,
    CONFIRMED, COMPENSATING, CANCELLED, FAILED, RECONCILIATION_REQUIRED;

    private static final Map<SagaState, Set<SagaState>> NEXT = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(INVENTORY_PENDING)),
            Map.entry(INVENTORY_PENDING, EnumSet.of(INVENTORY_RESERVED, CANCELLED, COMPENSATING)),
            Map.entry(INVENTORY_RESERVED, EnumSet.of(PAYMENT_PENDING)),
            Map.entry(PAYMENT_PENDING, EnumSet.of(PAYMENT_AUTHORIZED, COMPENSATING, RECONCILIATION_REQUIRED)),
            Map.entry(RECONCILIATION_REQUIRED, EnumSet.of(PAYMENT_AUTHORIZED, COMPENSATING, FAILED)),
            Map.entry(PAYMENT_AUTHORIZED, EnumSet.of(INVENTORY_CONFIRM_PENDING, COMPENSATING,
                    RECONCILIATION_REQUIRED)),
            Map.entry(INVENTORY_CONFIRM_PENDING, EnumSet.of(CONFIRMED, COMPENSATING)),
            Map.entry(COMPENSATING, EnumSet.of(CANCELLED, FAILED)),
            Map.entry(CONFIRMED, EnumSet.noneOf(SagaState.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(SagaState.class)),
            Map.entry(FAILED, EnumSet.noneOf(SagaState.class)));

    public boolean canMoveTo(SagaState next) {
        return NEXT.get(this).contains(next);
    }

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == FAILED;
    }
}
