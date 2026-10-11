package com.marketflow.order.application;

import com.marketflow.order.domain.Cancellation;
import com.marketflow.order.domain.Order;
import com.marketflow.order.domain.OrderStatus;
import com.marketflow.order.domain.saga.CheckoutSaga;
import com.marketflow.order.domain.saga.CheckoutSaga.Compensation;
import com.marketflow.order.domain.saga.SagaState;
import com.marketflow.order.persistence.CancellationRepository;
import com.marketflow.order.persistence.OrderRepository;
import com.marketflow.order.persistence.SagaRepository;
import com.marketflow.platform.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the checkout saga. Every method runs inside the consumer's local transaction (inbox insert +
 * saga row lock + state change + outbox commands commit together), so a reply is either fully applied or will be
 * redelivered and applied later - never half-applied.
 *
 * <p>Duplicate replies are removed by the inbox; replies that are legal but arrive in an unexpected state (late or
 * out of order) are handled explicitly: anything that would leave reserved stock or an open payment behind triggers
 * the matching compensating command, everything else is recorded and ignored.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CheckoutSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSagaOrchestrator.class);

    private final SagaRepository sagas;
    private final OrderRepository orders;
    private final CancellationRepository cancellations;
    private final SagaSupport s;

    public CheckoutSagaOrchestrator(SagaRepository sagas, OrderRepository orders,
                                    CancellationRepository cancellations, SagaSupport support) {
        this.sagas = sagas;
        this.orders = orders;
        this.cancellations = cancellations;
        this.s = support;
    }

    public void handle(EventEnvelope e, UUID orderId) {
        Optional<CheckoutSaga> found = sagas.findForUpdate(orderId);
        if (found.isEmpty()) {
            log.warn("Reply {} for unknown saga {} ignored", e.eventType(), orderId);
            return;
        }
        CheckoutSaga saga = found.get();
        Order order = orders.findDetailed(orderId).orElseThrow();
        String cause = e.eventId().toString();
        SagaState state = saga.getState();
        switch (e.eventType()) {
            case "InventoryReserved" -> {
                if (state == SagaState.INVENTORY_PENDING) {
                    s.move(saga, SagaState.INVENTORY_RESERVED, e.eventType(), e.eventId(), null);
                    s.move(saga, SagaState.PAYMENT_PENDING, "PaymentAuthorizationRequested", null, null);
                    s.authorizePayment(order, saga, cause);
                } else if (state == SagaState.CANCELLED || state == SagaState.FAILED
                        || state == SagaState.COMPENSATING && !saga.pending().contains(Compensation.INVENTORY)) {
                    // reservation arrived after we gave up on it: release so stock does not leak
                    s.note(saga, "LateInventoryReserved", e.eventId(), "releasing late reservation");
                    s.inventoryCommand(order, "InventoryReleaseRequested", "LATE_RESERVATION", cause);
                } else {
                    ignored(saga, e);
                }
            }
            case "InventoryReservationFailed" -> {
                if (state == SagaState.INVENTORY_PENDING) {
                    // reservations are all-or-nothing: nothing was reserved, nothing to compensate
                    s.move(saga, SagaState.CANCELLED, e.eventType(), e.eventId(), "out of stock");
                    cancelOrder(order, "OUT_OF_STOCK", cause);
                    s.meters().counter("saga.failures", "step", "inventory").increment();
                } else {
                    ignored(saga, e);
                }
            }
            case "PaymentAuthorized" -> {
                if (state == SagaState.PAYMENT_PENDING || state == SagaState.RECONCILIATION_REQUIRED) {
                    s.move(saga, SagaState.PAYMENT_AUTHORIZED, e.eventType(), e.eventId(), null);
                    s.paymentCommand(order, "PaymentCaptureRequested", null, cause);
                } else if (state == SagaState.CANCELLED || state == SagaState.FAILED) {
                    s.note(saga, "LatePaymentAuthorized", e.eventId(), "voiding late authorisation");
                    s.paymentCommand(order, "PaymentVoidRequested", "LATE_AUTHORIZATION", cause);
                } else {
                    ignored(saga, e); // e.g. duplicate after capture already requested
                }
            }
            case "PaymentFailed" -> {
                if (state == SagaState.PAYMENT_PENDING || state == SagaState.RECONCILIATION_REQUIRED) {
                    compensate(saga, order, "PAYMENT_DECLINED", EnumSet.of(Compensation.INVENTORY), e);
                    s.meters().counter("saga.failures", "step", "payment").increment();
                } else {
                    ignored(saga, e);
                }
            }
            case "PaymentOutcomeUnknown" -> {
                if (state == SagaState.PAYMENT_PENDING || state == SagaState.PAYMENT_AUTHORIZED) {
                    // a timeout is NOT a decline: wait for payment-service reconciliation
                    s.move(saga, SagaState.RECONCILIATION_REQUIRED, e.eventType(), e.eventId(), null);
                    s.meters().counter("saga.reconciliation", "reason", "unknown_outcome").increment();
                } else {
                    ignored(saga, e);
                }
            }
            case "PaymentCaptured" -> {
                if (state == SagaState.RECONCILIATION_REQUIRED) {
                    s.move(saga, SagaState.PAYMENT_AUTHORIZED, "ReconciledAsAuthorized", e.eventId(), null);
                    state = SagaState.PAYMENT_AUTHORIZED;
                }
                if (state == SagaState.PAYMENT_AUTHORIZED) {
                    s.move(saga, SagaState.INVENTORY_CONFIRM_PENDING, e.eventType(), e.eventId(), null);
                    s.inventoryCommand(order, "InventoryConfirmationRequested", null, cause);
                } else if (state == SagaState.CANCELLED || state == SagaState.FAILED) {
                    s.note(saga, "LatePaymentCaptured", e.eventId(), "refunding late capture");
                    s.paymentCommand(order, "RefundRequested", "LATE_CAPTURE", cause);
                } else {
                    ignored(saga, e);
                }
            }
            case "PaymentCaptureFailed" -> {
                if (state == SagaState.PAYMENT_AUTHORIZED || state == SagaState.RECONCILIATION_REQUIRED) {
                    compensate(saga, order, "CAPTURE_FAILED",
                            EnumSet.of(Compensation.INVENTORY, Compensation.PAYMENT_VOID), e);
                    s.meters().counter("saga.failures", "step", "capture").increment();
                } else {
                    ignored(saga, e);
                }
            }
            case "InventoryReservationConfirmed" -> {
                if (state == SagaState.INVENTORY_CONFIRM_PENDING) {
                    s.move(saga, SagaState.CONFIRMED, e.eventType(), e.eventId(), null);
                    OrderStatus prev = order.confirm();
                    s.orderStatus(order, prev, "payment captured and stock confirmed", null);
                    s.orderEvent(order, "OrderConfirmed", null, true, cause);
                    s.meters().counter("checkout.completed", "outcome", "confirmed").increment();
                } else {
                    ignored(saga, e);
                }
            }
            case "InventoryConfirmationFailed" -> {
                if (state == SagaState.INVENTORY_CONFIRM_PENDING) {
                    // money was captured but the goods are gone (reservation expired and stock sold): refund
                    compensate(saga, order, "STOCK_UNAVAILABLE_AFTER_PAYMENT", EnumSet.of(Compensation.PAYMENT_REFUND),
                            e);
                    s.meters().counter("saga.failures", "step", "inventory_confirm").increment();
                } else {
                    ignored(saga, e);
                }
            }
            case "InventoryReservationReleased" -> acknowledge(saga, order, Compensation.INVENTORY, e);
            case "PaymentVoided" -> acknowledge(saga, order, Compensation.PAYMENT_VOID, e);
            case "RefundCompleted" -> {
                if (state == SagaState.COMPENSATING) {
                    acknowledge(saga, order, Compensation.PAYMENT_REFUND, e);
                } else if (state == SagaState.CONFIRMED) {
                    cancellationStep(order, true, false, e);
                } else {
                    ignored(saga, e);
                }
            }
            case "InventoryRestocked" -> {
                if (state == SagaState.CONFIRMED) {
                    cancellationStep(order, false, true, e);
                } else {
                    ignored(saga, e);
                }
            }
            case "RefundFailed" -> {
                if (state == SagaState.CONFIRMED) {
                    cancellations.findForUpdate(orderId).ifPresent(c -> c.fail("REFUND_FAILED"));
                    OrderStatus prev = order.refundFailed("Refund failed - support will contact you");
                    s.orderStatus(order, prev, "refund failed", null);
                    s.event(order, "OrderStatusChanged", new OrderMessages.OrderStatusChanged(order.getId(),
                            order.getCustomerId(), order.getStatus().name(), "REFUND_FAILED"), cause);
                } else {
                    s.note(saga, e.eventType(), e.eventId(), "refund failed during compensation - manual action");
                    saga.recordError("RefundFailed during compensation");
                    s.meters().counter("saga.failures", "step", "refund").increment();
                }
            }
            default -> ignored(saga, e);
        }
    }

    /** Fired by {@link SagaTimeoutScanner} when a saga's deadline passed without the expected reply. */
    public void onTimeout(UUID orderId) {
        CheckoutSaga saga = sagas.findForUpdate(orderId).orElse(null);
        if (saga == null || saga.getState().isTerminal() || saga.getDeadlineAt().isAfter(java.time.Instant.now())) {
            return; // a reply arrived in the meantime
        }
        Order order = orders.findDetailed(orderId).orElseThrow();
        SagaState state = saga.getState();
        s.meters().counter("saga.timeouts", "state", state.name()).increment();
        boolean canRetry = saga.getAttempt() < s.maxAttempts();
        switch (state) {
            case INVENTORY_PENDING, INVENTORY_RESERVED -> {
                if (canRetry) {
                    retry(saga, "InventoryReservationRequested");
                    s.inventoryCommand(order, "InventoryReservationRequested", null, null);
                } else {
                    // inventory never answered: release (creates a tombstone if the reserve was never processed)
                    compensate(saga, order, "INVENTORY_TIMEOUT", EnumSet.of(Compensation.INVENTORY), null);
                }
            }
            case PAYMENT_PENDING -> {
                // never assume a timed-out payment failed: ask again (idempotent) and switch to reconciliation
                s.move(saga, SagaState.RECONCILIATION_REQUIRED, "PaymentTimeout", null, null);
                s.authorizePayment(order, saga, null);
                s.meters().counter("saga.reconciliation", "reason", "timeout").increment();
            }
            case RECONCILIATION_REQUIRED -> {
                if (saga.getAttempt() < s.maxReconciliationAttempts()) {
                    retry(saga, "PaymentStatusPoll");
                    s.authorizePayment(order, saga, null); // payment-service re-publishes the current outcome
                } else {
                    s.move(saga, SagaState.FAILED, "ReconciliationExhausted", null,
                            "payment outcome still unknown - manual reconciliation required");
                    OrderStatus prev = order.fail("PAYMENT_UNRESOLVED");
                    s.orderStatus(order, prev, "payment outcome unresolved", null);
                    s.event(order, "OrderStatusChanged", new OrderMessages.OrderStatusChanged(order.getId(),
                            order.getCustomerId(), "FAILED", "PAYMENT_UNRESOLVED"), null);
                }
            }
            case PAYMENT_AUTHORIZED -> {
                if (canRetry) {
                    retry(saga, "PaymentCaptureRequested");
                    s.paymentCommand(order, "PaymentCaptureRequested", null, null);
                } else {
                    s.move(saga, SagaState.RECONCILIATION_REQUIRED, "CaptureTimeout", null, null);
                    s.authorizePayment(order, saga, null);
                }
            }
            case INVENTORY_CONFIRM_PENDING -> {
                // payment is captured: keep (idempotently) asking inventory, alert if it takes too long
                int attempt = retry(saga, "InventoryConfirmationRequested");
                if (attempt > s.maxAttempts()) {
                    s.meters().counter("saga.stuck", "state", state.name()).increment();
                    log.error("Saga {} stuck in {} after {} attempts", orderId, state, attempt);
                }
                s.inventoryCommand(order, "InventoryConfirmationRequested", null, null);
            }
            case COMPENSATING -> {
                if (saga.getAttempt() >= s.maxAttempts() * 3) {
                    s.move(saga, SagaState.FAILED, "CompensationExhausted", null,
                            "outstanding: " + saga.pending());
                    OrderStatus prev = order.fail("COMPENSATION_INCOMPLETE");
                    s.orderStatus(order, prev, "compensation incomplete", null);
                    return;
                }
                retry(saga, "CompensationRetry");
                sendCompensations(saga, order, null);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void compensate(CheckoutSaga saga, Order order, String reason, EnumSet<Compensation> needed,
                            EventEnvelope cause) {
        s.move(saga, SagaState.COMPENSATING, cause == null ? "Timeout" : cause.eventType(),
                cause == null ? null : cause.eventId(), reason);
        saga.beginCompensation(reason, needed);
        sendCompensations(saga, order, cause == null ? null : cause.eventId().toString());
        s.meters().counter("saga.compensations", "reason", reason).increment();
    }

    private void sendCompensations(CheckoutSaga saga, Order order, String cause) {
        for (Compensation c : saga.pending()) {
            switch (c) {
                case INVENTORY -> s.inventoryCommand(order, "InventoryReleaseRequested", saga.getCompensationReason(),
                        cause);
                case PAYMENT_VOID -> s.paymentCommand(order, "PaymentVoidRequested", saga.getCompensationReason(),
                        cause);
                case PAYMENT_REFUND -> s.paymentCommand(order, "RefundRequested", saga.getCompensationReason(),
                        cause);
            }
        }
    }

    private void acknowledge(CheckoutSaga saga, Order order, Compensation done, EventEnvelope e) {
        if (saga.getState() != SagaState.COMPENSATING) {
            ignored(saga, e);
            return;
        }
        if (!saga.pending().contains(done)) {
            ignored(saga, e);
            return;
        }
        s.note(saga, e.eventType(), e.eventId(), "compensation acknowledged: " + done);
        if (saga.compensated(done)) {
            s.move(saga, SagaState.CANCELLED, "CompensationComplete", e.eventId(), saga.getCompensationReason());
            cancelOrder(order, saga.getCompensationReason(), e.eventId().toString());
        }
    }

    private void cancelOrder(Order order, String reason, String cause) {
        OrderStatus prev = order.cancel(reason);
        s.orderStatus(order, prev, reason, null);
        s.orderEvent(order, "OrderCancelled", reason, false, cause);
        s.meters().counter("checkout.completed", "outcome", "cancelled", "reason", reason).increment();
    }

    /** Post-confirmation cancellation: complete when BOTH refund and restock are acknowledged. */
    private void cancellationStep(Order order, boolean refunded, boolean restocked, EventEnvelope e) {
        Cancellation c = cancellations.findForUpdate(order.getId()).orElse(null);
        if (c == null) {
            log.warn("{} for order {} without a cancellation request ignored", e.eventType(), order.getId());
            return;
        }
        boolean complete = refunded ? c.markRefunded() : c.markRestocked();
        if (complete && order.getStatus() == OrderStatus.CANCELLATION_REQUESTED) {
            OrderStatus prev = order.cancel(c.getReason());
            s.orderStatus(order, prev, "refunded and restocked", null);
            s.orderEvent(order, "OrderCancelled", c.getReason(), true, e.eventId().toString());
        }
    }

    private int retry(CheckoutSaga saga, String what) {
        int attempt = saga.retry(s.timeoutFor(saga.getState()));
        s.note(saga, "Retry:" + what, null, "attempt " + attempt);
        return attempt;
    }

    private void ignored(CheckoutSaga saga, EventEnvelope e) {
        log.info("Saga {} in {} ignored {} ({})", saga.getOrderId(), saga.getState(), e.eventType(), e.eventId());
        s.note(saga, "Ignored:" + e.eventType(), e.eventId(), "not applicable in " + saga.getState());
        s.meters().counter("saga.ignored_replies", "event", e.eventType()).increment();
    }
}
