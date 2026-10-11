package com.marketflow.order.domain.saga;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaStateTest {

    private static final Duration T = Duration.ofSeconds(30);

    @Test
    void happyPathIsLegal() {
        CheckoutSaga s = CheckoutSaga.start(UUID.randomUUID(), "tok_visa");
        s.moveTo(SagaState.INVENTORY_PENDING, T);
        s.moveTo(SagaState.INVENTORY_RESERVED, T);
        s.moveTo(SagaState.PAYMENT_PENDING, T);
        s.moveTo(SagaState.PAYMENT_AUTHORIZED, T);
        s.moveTo(SagaState.INVENTORY_CONFIRM_PENDING, T);
        s.moveTo(SagaState.CONFIRMED, T);
        assertThat(s.getState().isTerminal()).isTrue();
        assertThat(s.getCompletedAt()).isNotNull();
    }

    @Test
    void cannotSkipSteps() {
        CheckoutSaga s = CheckoutSaga.start(UUID.randomUUID(), "tok_visa");
        s.moveTo(SagaState.INVENTORY_PENDING, T);
        assertThatThrownBy(() -> s.moveTo(SagaState.PAYMENT_AUTHORIZED, T))
                .isInstanceOf(IllegalSagaTransitionException.class);
        assertThatThrownBy(() -> s.moveTo(SagaState.CONFIRMED, T))
                .isInstanceOf(IllegalSagaTransitionException.class);
    }

    @Test
    void terminalStatesAreFinal() {
        for (SagaState terminal : EnumSet.of(SagaState.CONFIRMED, SagaState.CANCELLED, SagaState.FAILED)) {
            for (SagaState any : SagaState.values()) {
                assertThat(terminal.canMoveTo(any)).as(terminal + "->" + any).isFalse();
            }
        }
    }

    @Test
    void paymentUncertaintyNeverJumpsStraightToCancelled() {
        assertThat(SagaState.PAYMENT_PENDING.canMoveTo(SagaState.CANCELLED)).isFalse();
        assertThat(SagaState.RECONCILIATION_REQUIRED.canMoveTo(SagaState.CANCELLED)).isFalse();
        assertThat(SagaState.PAYMENT_PENDING.canMoveTo(SagaState.RECONCILIATION_REQUIRED)).isTrue();
    }

    @Test
    void compensationBookkeeping() {
        CheckoutSaga s = CheckoutSaga.start(UUID.randomUUID(), "tok");
        s.beginCompensation("CAPTURE_FAILED",
                EnumSet.of(CheckoutSaga.Compensation.INVENTORY, CheckoutSaga.Compensation.PAYMENT_VOID));
        assertThat(s.compensated(CheckoutSaga.Compensation.INVENTORY)).isFalse();
        assertThat(s.compensated(CheckoutSaga.Compensation.INVENTORY)).isFalse(); // duplicate ack is harmless
        assertThat(s.compensated(CheckoutSaga.Compensation.PAYMENT_VOID)).isTrue();
        assertThat(s.pending()).isEmpty();
    }

    @Test
    void retryArmsNewDeadlineAndCountsAttempts() {
        CheckoutSaga s = CheckoutSaga.start(UUID.randomUUID(), "tok");
        s.moveTo(SagaState.INVENTORY_PENDING, T);
        var before = s.getDeadlineAt();
        assertThat(s.retry(T)).isEqualTo(1);
        assertThat(s.retry(T)).isEqualTo(2);
        assertThat(s.getDeadlineAt()).isAfterOrEqualTo(before);
        s.moveTo(SagaState.INVENTORY_RESERVED, T);
        assertThat(s.getAttempt()).isZero();
    }
}
