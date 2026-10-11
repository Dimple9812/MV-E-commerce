package com.marketflow.order.application;

import com.marketflow.order.application.OrderMessages.InventoryCommand;
import com.marketflow.order.application.OrderMessages.InventoryLine;
import com.marketflow.order.config.OrderProperties;
import com.marketflow.order.domain.Order;
import com.marketflow.order.domain.OrderItem;
import com.marketflow.order.domain.OrderStatus;
import com.marketflow.order.domain.OrderStatusHistory;
import com.marketflow.order.domain.saga.CheckoutSaga;
import com.marketflow.order.domain.saga.SagaState;
import com.marketflow.order.domain.saga.SagaTransition;
import com.marketflow.order.persistence.SagaTransitionRepository;
import com.marketflow.order.persistence.StatusHistoryRepository;
import com.marketflow.platform.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** Shared mechanics: saga transitions (+ audit row + metrics), outgoing commands and order events. */
@Component
public class SagaSupport {

    private static final Logger log = LoggerFactory.getLogger(SagaSupport.class);

    private final SagaTransitionRepository transitions;
    private final StatusHistoryRepository history;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;
    private final OrderProperties.Saga cfg;

    public SagaSupport(SagaTransitionRepository transitions, StatusHistoryRepository history, OutboxWriter outbox,
                       MeterRegistry meters, OrderProperties props) {
        this.transitions = transitions;
        this.history = history;
        this.outbox = outbox;
        this.meters = meters;
        this.cfg = props.saga();
    }

    public Duration timeoutFor(SagaState state) {
        return switch (state) {
            case INVENTORY_PENDING, INVENTORY_RESERVED -> cfg.inventoryTimeout();
            case PAYMENT_PENDING -> cfg.paymentTimeout();
            case PAYMENT_AUTHORIZED -> cfg.captureTimeout();
            case INVENTORY_CONFIRM_PENDING -> cfg.confirmTimeout();
            case COMPENSATING -> cfg.compensationTimeout();
            case RECONCILIATION_REQUIRED -> cfg.reconciliationInterval();
            default -> Duration.ofDays(3650); // terminal: never times out
        };
    }

    public void move(CheckoutSaga saga, SagaState to, String trigger, UUID eventId, String detail) {
        SagaState from = saga.moveTo(to, timeoutFor(to));
        transitions.save(new SagaTransition(saga.getOrderId(), from, to, trigger, eventId, detail));
        meters.counter("saga.transitions", "from", from.name(), "to", to.name()).increment();
        log.info("Saga {} {} -> {} ({})", saga.getOrderId(), from, to, trigger);
        if (to.isTerminal()) {
            meters.timer("saga.duration", "outcome", to.name())
                    .record(Duration.between(saga.getCreatedAt(), saga.getCompletedAt()));
        }
    }

    public void note(CheckoutSaga saga, String trigger, UUID eventId, String detail) {
        transitions.save(new SagaTransition(saga.getOrderId(), saga.getState(), saga.getState(), trigger, eventId,
                detail));
    }

    public int maxAttempts() {
        return cfg.maxAttempts();
    }

    public int maxReconciliationAttempts() {
        return cfg.maxReconciliationAttempts();
    }

    public MeterRegistry meters() {
        return meters;
    }

    public void orderStatus(Order order, OrderStatus previous, String reason, UUID actor) {
        history.save(new OrderStatusHistory(order.getId(), null, previous == null ? null : previous.name(),
                order.getStatus().name(), reason, actor));
    }

    public void suborderStatus(Order order, UUID suborderId, String from, String to, String reason, UUID actor) {
        history.save(new OrderStatusHistory(order.getId(), suborderId, from, to, reason, actor));
    }

    // ---------- outgoing commands ----------

    public void inventoryCommand(Order order, String type, String reason, String causation) {
        var lines = order.allItems().stream()
                .map((OrderItem i) -> new InventoryLine(i.getVariantId(), i.getSku(), i.getQuantity())).toList();
        outbox.append(OrderMessages.INVENTORY_COMMANDS, type, 1, OrderMessages.AGGREGATE, order.getId().toString(),
                new InventoryCommand(order.getId(), lines, reason), causation);
    }

    public void authorizePayment(Order order, CheckoutSaga saga, String causation) {
        outbox.append(OrderMessages.PAYMENT_COMMANDS, "PaymentAuthorizationRequested", 1, OrderMessages.AGGREGATE,
                order.getId().toString(), new OrderMessages.PaymentAuthorizationRequested(order.getId(),
                        order.getCustomerId(), order.getGrandTotal(), order.getCurrency(), saga.getPaymentToken()),
                causation);
    }

    public void paymentCommand(Order order, String type, String reason, String causation) {
        outbox.append(OrderMessages.PAYMENT_COMMANDS, type, 1, OrderMessages.AGGREGATE, order.getId().toString(),
                new OrderMessages.PaymentCommand(order.getId(), order.getGrandTotal(), order.getCurrency(), reason),
                causation);
    }

    public void orderEvent(Order order, String type, String reason, boolean wasConfirmed, String causation) {
        outbox.append(OrderMessages.ORDER_EVENTS, type, 1, OrderMessages.AGGREGATE, order.getId().toString(),
                OrderMessages.OrderSnapshot.of(order, reason, wasConfirmed), causation);
    }

    public void event(Order order, String type, Object payload, String causation) {
        outbox.append(OrderMessages.ORDER_EVENTS, type, 1, OrderMessages.AGGREGATE, order.getId().toString(), payload,
                causation);
    }
}
