package com.marketflow.payment.application;

import com.marketflow.payment.application.PaymentStore.Payment;
import com.marketflow.payment.application.PaymentStore.Refund;
import com.marketflow.payment.provider.PaymentProvider;
import com.marketflow.payment.provider.ProviderGateway;
import com.marketflow.payment.provider.ProviderTimeoutException;
import com.marketflow.payment.provider.ProviderUnavailableException;
import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment command handling. Pattern for every operation:
 * <ol>
 *   <li>short DB transaction: create/lease the payment (the lease stops concurrent duplicates),</li>
 *   <li>provider call OUTSIDE any DB transaction (no locks held during network I/O), with an idempotency key,</li>
 *   <li>short DB transaction: record the attempt, apply the result, append the outcome event to the outbox.</li>
 * </ol>
 * A crash between 2 and 3 leaves the lease in place; reconciliation picks it up after 60 s and asks the provider
 * what actually happened, so no outcome is lost and nothing is executed twice.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentStore store;
    private final ProviderGateway gateway;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public PaymentService(PaymentStore store, ProviderGateway gateway, OutboxWriter outbox, AuditRecorder audit,
                          TransactionTemplate tx, MeterRegistry meters) {
        this.store = store;
        this.gateway = gateway;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = tx;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ authorize

    public void authorize(PaymentEvents.AuthorizeCommand cmd, String causation) {
        UUID newId = UUID.randomUUID();
        Payment leased = tx.execute(s -> {
            if (store.insertPending(newId, cmd.orderId(), cmd.customerId(), cmd.amount(), cmd.currency(),
                    gateway.providerName(), cmd.paymentToken())) {
                return store.byOrder(cmd.orderId(), false).orElseThrow();
            }
            // repeated command: never authorise twice - report what we know (or reconcile below)
            Payment existing = store.byOrder(cmd.orderId(), true).orElseThrow();
            if (existing.inFlight() == null && !isUnresolved(existing)) {
                republish(existing, causation);
            }
            return null;
        });
        if (leased == null) {
            store.byOrder(cmd.orderId(), false).filter(p -> p.inFlight() == null && isUnresolved(p))
                    .ifPresent(this::reconcile);
            return;
        }
        callAuthorize(leased, causation);
    }

    private void callAuthorize(Payment p, String causation) {
        long start = System.nanoTime();
        try {
            PaymentProvider.Result r = gateway.call("authorize", () -> gateway.provider().authorize(
                    p.orderId().toString(), p.amount(), p.currency(), p.token(), "pay:" + p.orderId() + ":authorize"));
            applyAuthorization(p, r, ms(start), causation);
        } catch (ProviderTimeoutException e) {
            tx.executeWithoutResult(s -> {
                store.attempt(p.id(), "AUTHORIZE", "TIMEOUT", null, null, ms(start));
                store.update(p.id(), "UNKNOWN", null, null);
                event(p, "PaymentOutcomeUnknown", "UNKNOWN", "provider_timeout", causation);
            });
            meters.counter("payment.outcomes", "operation", "authorize", "result", "unknown").increment();
        } catch (ProviderUnavailableException e) {
            tx.executeWithoutResult(s -> {
                store.attempt(p.id(), "AUTHORIZE", "NOT_ATTEMPTED", null, "provider_unavailable", ms(start));
                store.release(p.id()); // stays PENDING; reconciliation retries with the same idempotency key
            });
            meters.counter("payment.outcomes", "operation", "authorize", "result", "not_attempted").increment();
        }
    }

    private void applyAuthorization(Payment p, PaymentProvider.Result r, long ms, String causation) {
        tx.executeWithoutResult(s -> {
            store.attempt(p.id(), "AUTHORIZE", r.status().name(), r.providerPaymentId(), r.code(), ms);
            switch (r.status()) {
                case AUTHORIZED -> {
                    store.update(p.id(), "AUTHORIZED", r.providerPaymentId(), null);
                    event(p, "PaymentAuthorized", "AUTHORIZED", null, causation);
                }
                case DECLINED -> {
                    store.update(p.id(), "DECLINED", r.providerPaymentId(), r.code());
                    event(p, "PaymentFailed", "DECLINED", r.code(), causation);
                }
                case PENDING -> store.update(p.id(), "PENDING", r.providerPaymentId(), null); // webhook follows
                default -> {
                    store.update(p.id(), "UNKNOWN", r.providerPaymentId(), r.status().name());
                    event(p, "PaymentOutcomeUnknown", "UNKNOWN", r.status().name(), causation);
                }
            }
        });
        meters.counter("payment.outcomes", "operation", "authorize", "result", r.status().name().toLowerCase())
                .increment();
    }

    // ------------------------------------------------------------------ capture

    public void capture(UUID orderId, String causation) {
        Optional<Payment> leased = tx.execute(s -> store.lease(orderId, "CAPTURE", "AUTHORIZED"));
        if (leased.isEmpty()) {
            tx.executeWithoutResult(s -> store.byOrder(orderId, true).ifPresentOrElse(p -> {
                if (p.inFlight() != null || isUnresolved(p)) {
                    return; // another worker / reconciliation owns it
                }
                switch (p.status()) {
                    case "CAPTURED", "REFUNDED", "PARTIALLY_REFUNDED" ->
                            event(p, "PaymentCaptured", p.status(), "already_captured", causation);
                    default -> event(p, "PaymentCaptureFailed", p.status(), "not_capturable", causation);
                }
            }, () -> log.warn("Capture requested for unknown payment of order {}", orderId)));
            return;
        }
        Payment p = leased.get();
        long start = System.nanoTime();
        try {
            PaymentProvider.Result r = gateway.call("capture", () -> gateway.provider().capture(p.providerPaymentId(),
                    p.amount(), "pay:" + orderId + ":capture"));
            tx.executeWithoutResult(s -> {
                store.attempt(p.id(), "CAPTURE", r.status().name(), r.providerPaymentId(), r.code(), ms(start));
                if (r.status() == PaymentProvider.Status.CAPTURED) {
                    store.update(p.id(), "CAPTURED", null, null);
                    event(p, "PaymentCaptured", "CAPTURED", null, causation);
                } else {
                    store.update(p.id(), "CAPTURE_FAILED", null, r.code());
                    event(p, "PaymentCaptureFailed", "CAPTURE_FAILED", r.code(), causation);
                }
            });
            meters.counter("payment.outcomes", "operation", "capture", "result", r.status().name().toLowerCase())
                    .increment();
        } catch (ProviderTimeoutException e) {
            tx.executeWithoutResult(s -> {
                store.attempt(p.id(), "CAPTURE", "TIMEOUT", null, null, ms(start));
                store.update(p.id(), "UNKNOWN", null, "capture_timeout");
                event(p, "PaymentOutcomeUnknown", "UNKNOWN", "capture_timeout", causation);
            });
        } catch (ProviderUnavailableException e) {
            tx.executeWithoutResult(s -> {
                store.attempt(p.id(), "CAPTURE", "NOT_ATTEMPTED", null, "provider_unavailable", ms(start));
                store.release(p.id()); // still AUTHORIZED; the saga re-sends the capture after its timeout
            });
        }
    }

    // ------------------------------------------------------------------ void

    public void voidPayment(UUID orderId, String reason, String causation) {
        Optional<Payment> leased = tx.execute(s -> store.lease(orderId, "VOID", "AUTHORIZED", "CAPTURE_FAILED"));
        if (leased.isEmpty()) {
            Optional<Payment> current = tx.execute(s -> store.byOrder(orderId, true));
            if (current.isPresent() && current.get().inFlight() == null) {
                Payment p = current.get();
                if (p.status().equals("CAPTURED")) {
                    // money already taken (race): refund in full instead, then acknowledge the void
                    refund(orderId, null, "VOID_AFTER_CAPTURE: " + reason, "void:" + orderId, null, causation);
                    tx.executeWithoutResult(s -> event(p, "PaymentVoided", "REFUNDED", "refunded_instead", causation));
                } else if (!isUnresolved(p)) {
                    tx.executeWithoutResult(s -> event(p, "PaymentVoided", p.status(), "nothing_to_void", causation));
                }
            } else if (current.isEmpty()) {
                log.info("Void for order {} without payment: nothing to do", orderId);
            }
            return;
        }
        Payment p = leased.get();
        long start = System.nanoTime();
        try {
            PaymentProvider.Result r = gateway.call("void", () -> gateway.provider().voidAuthorization(
                    p.providerPaymentId(), "pay:" + orderId + ":void"));
            tx.executeWithoutResult(s -> {
                store.attempt(p.id(), "VOID", r.status().name(), r.providerPaymentId(), r.code(), ms(start));
                if (r.status() == PaymentProvider.Status.VOIDED) {
                    store.update(p.id(), "VOIDED", null, null);
                    event(p, "PaymentVoided", "VOIDED", null, causation);
                } else {
                    store.release(p.id());
                    log.warn("Void of payment {} returned {}", p.id(), r.status());
                }
            });
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            tx.executeWithoutResult(s -> {
                store.attempt(p.id(), "VOID", "ERROR", null, e.getClass().getSimpleName(), ms(start));
                store.release(p.id()); // voids are idempotent: the saga's compensation retry will repeat it
            });
        }
    }

    // ------------------------------------------------------------------ refunds

    /**
     * @param amount    null = refund the remaining captured amount
     * @param refundKey idempotency key of this refund request
     */
    public Optional<Refund> refund(UUID orderId, BigDecimal amount, String reason, String refundKey, UUID requestedBy,
                                   String causation) {
        record Plan(Payment payment, Refund refund, boolean call) {
        }
        Plan plan = tx.execute(s -> {
            Payment p = store.byOrder(orderId, true).orElse(null);
            Optional<Refund> existing = store.refundByKey(refundKey);
            if (existing.isPresent()) {
                Refund r = existing.get();
                if (!r.status().equals("PENDING")) {
                    refundEvent(p, r, r.status().equals("SUCCEEDED") ? "RefundCompleted" : "RefundFailed", causation);
                    return new Plan(p, r, false);
                }
                return new Plan(p, r, true); // retry the pending refund with the same provider key
            }
            if (p == null || !(p.status().equals("CAPTURED") || p.status().equals("PARTIALLY_REFUNDED"))) {
                if (p != null) {
                    event(p, "RefundFailed", p.status(), "not_refundable", causation);
                }
                return null;
            }
            BigDecimal remaining = p.amount().subtract(p.refundedAmount()).subtract(store.pendingRefunds(p.id()));
            BigDecimal value = amount == null ? remaining : amount;
            if (value.signum() <= 0 || value.compareTo(remaining) > 0) {
                throw ApiException.unprocessable("invalid_refund_amount",
                        "Refund must be between 0.01 and the remaining " + remaining);
            }
            UUID id = UUID.randomUUID();
            store.insertRefund(id, p.id(), value, reason, refundKey, requestedBy);
            audit.record(requestedBy, requestedBy == null ? "SYSTEM" : "ADMIN", "payment.refund_requested", "Payment",
                    p.id().toString(), Map.of("amount", value.toPlainString(), "reason", reason));
            return new Plan(p, store.refundByKey(refundKey).orElseThrow(), true);
        });
        if (plan == null || !plan.call()) {
            return plan == null ? Optional.empty() : Optional.of(plan.refund());
        }
        Payment p = plan.payment();
        Refund r = plan.refund();
        long start = System.nanoTime();
        try {
            PaymentProvider.Result res = gateway.call("refund", () -> gateway.provider().refund(p.providerPaymentId(),
                    r.amount(), "refund:" + r.refundKey()));
            tx.executeWithoutResult(s -> {
                store.attempt(p.id(), "REFUND", res.status().name(), res.providerPaymentId(), res.code(), ms(start));
                if (res.status() == PaymentProvider.Status.REFUNDED) {
                    store.completeRefund(r.id(), "SUCCEEDED", res.providerPaymentId());
                    store.addRefunded(p.id(), r.amount());
                    refundEvent(p, store.refundByKey(r.refundKey()).orElseThrow(), "RefundCompleted", causation);
                } else {
                    store.completeRefund(r.id(), "FAILED", null);
                    refundEvent(p, store.refundByKey(r.refundKey()).orElseThrow(), "RefundFailed", causation);
                }
            });
            meters.counter("payment.refunds", "result", res.status().name().toLowerCase()).increment();
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            tx.executeWithoutResult(s -> store.attempt(p.id(), "REFUND", "ERROR", null, e.getClass().getSimpleName(),
                    ms(start))); // refund stays PENDING; repeating the request retries it idempotently
        }
        return store.refundByKey(refundKey);
    }

    // ------------------------------------------------------------------ webhooks & reconciliation

    /** Applies a verified provider notification. Illegal/irrelevant transitions are ignored. */
    public void applyWebhook(String type, String providerPaymentId) {
        tx.executeWithoutResult(s -> store.byProviderId(providerPaymentId, true).ifPresent(p -> {
            switch (type) {
                case "payment.authorized" -> {
                    if (p.status().equals("PENDING") || p.status().equals("UNKNOWN")) {
                        store.update(p.id(), "AUTHORIZED", null, null);
                        event(p, "PaymentAuthorized", "AUTHORIZED", "webhook", null);
                    }
                }
                case "payment.failed" -> {
                    if (p.status().equals("PENDING") || p.status().equals("UNKNOWN")) {
                        store.update(p.id(), "DECLINED", null, "webhook_declined");
                        event(p, "PaymentFailed", "DECLINED", "webhook_declined", null);
                    }
                }
                case "payment.captured" -> {
                    if (p.status().equals("AUTHORIZED") || p.status().equals("UNKNOWN")) {
                        store.update(p.id(), "CAPTURED", null, null);
                        event(p, "PaymentCaptured", "CAPTURED", "webhook", null);
                    }
                }
                default -> log.info("Webhook type {} has no effect on payment {}", type, p.id());
            }
        }));
    }

    @Scheduled(fixedDelayString = "${marketflow.payment.reconciliation-interval:5s}")
    public void reconcileDue() {
        for (Payment p : store.dueForReconciliation(20)) {
            try {
                reconcile(p);
            } catch (RuntimeException e) {
                log.warn("Reconciliation of payment {} failed: {}", p.id(), e.getMessage());
            }
        }
    }

    /** Asks the provider what really happened and adopts that as the truth. */
    void reconcile(Payment p) {
        store.bumpReconAttempts(p.id());
        PaymentProvider.Result remote;
        try {
            remote = gateway.call("query", () -> gateway.provider().query(p.orderId().toString())
                    .orElse(new PaymentProvider.Result(PaymentProvider.Status.NOT_FOUND, null, null)));
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            meters.counter("payment.reconciliations", "result", "provider_unreachable").increment();
            return;
        }
        String result;
        if (remote.status() == PaymentProvider.Status.NOT_FOUND) {
            // the provider never saw the request: authorising again is safe (same idempotency key)
            if ("AUTHORIZE".equals(p.lastOperation()) || p.status().equals("PENDING")) {
                Optional<Payment> leased = tx.execute(s -> store.lease(p.orderId(), "AUTHORIZE", "PENDING", "UNKNOWN"));
                if (leased.isEmpty() && p.inFlight() != null) {
                    tx.executeWithoutResult(s -> store.release(p.id()));
                    leased = tx.execute(s -> store.lease(p.orderId(), "AUTHORIZE", "PENDING", "UNKNOWN"));
                }
                leased.ifPresent(l -> callAuthorize(l, null));
                result = "reauthorized";
            } else {
                result = "not_found";
            }
        } else {
            PaymentProvider.Result r = remote;
            result = r.status().name().toLowerCase();
            tx.executeWithoutResult(s -> {
                Payment cur = store.byOrder(p.orderId(), true).orElseThrow();
                switch (r.status()) {
                    case AUTHORIZED -> {
                        store.update(cur.id(), "AUTHORIZED", r.providerPaymentId(), null);
                        if (!"CAPTURE".equals(cur.lastOperation())) {
                            event(cur, "PaymentAuthorized", "AUTHORIZED", "reconciled", null);
                        } // after a lost capture response the saga simply retries the (idempotent) capture
                    }
                    case DECLINED -> {
                        store.update(cur.id(), "DECLINED", r.providerPaymentId(), "reconciled_decline");
                        event(cur, "PaymentFailed", "DECLINED", "reconciled_decline", null);
                    }
                    case CAPTURED -> {
                        store.update(cur.id(), "CAPTURED", r.providerPaymentId(), null);
                        event(cur, "PaymentCaptured", "CAPTURED", "reconciled", null);
                    }
                    case CAPTURE_FAILED -> {
                        store.update(cur.id(), "CAPTURE_FAILED", r.providerPaymentId(), "reconciled");
                        event(cur, "PaymentCaptureFailed", "CAPTURE_FAILED", "reconciled", null);
                    }
                    case VOIDED -> {
                        store.update(cur.id(), "VOIDED", r.providerPaymentId(), null);
                        event(cur, "PaymentVoided", "VOIDED", "reconciled", null);
                    }
                    case PENDING -> store.release(cur.id()); // still processing at the provider; check again later
                    default -> store.release(cur.id());
                }
            });
        }
        meters.counter("payment.reconciliations", "result", result).increment();
        log.info("Reconciled payment {} for order {}: {}", p.id(), p.orderId(), result);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isUnresolved(Payment p) {
        return p.status().equals("PENDING") || p.status().equals("UNKNOWN");
    }

    private void republish(Payment p, String causation) {
        switch (p.status()) {
            case "AUTHORIZED" -> event(p, "PaymentAuthorized", "AUTHORIZED", "replay", causation);
            case "DECLINED" -> event(p, "PaymentFailed", "DECLINED", p.failureCode(), causation);
            case "CAPTURED", "REFUNDED", "PARTIALLY_REFUNDED" -> event(p, "PaymentCaptured", p.status(), "replay",
                    causation);
            case "CAPTURE_FAILED" -> event(p, "PaymentCaptureFailed", "CAPTURE_FAILED", p.failureCode(), causation);
            case "VOIDED" -> event(p, "PaymentVoided", "VOIDED", "replay", causation);
            default -> event(p, "PaymentOutcomeUnknown", p.status(), "replay", causation);
        }
    }

    private void event(Payment p, String type, String status, String code, String causation) {
        outbox.append(PaymentEvents.TOPIC, type, 1, PaymentEvents.AGGREGATE, p.orderId().toString(),
                new PaymentEvents.PaymentOutcome(p.orderId(), p.id(), p.customerId(), p.amount(), p.currency(), status,
                        code, null, null), causation);
    }

    private void refundEvent(Payment p, Refund r, String type, String causation) {
        if (p == null) {
            return;
        }
        outbox.append(PaymentEvents.TOPIC, type, 1, PaymentEvents.AGGREGATE, p.orderId().toString(),
                new PaymentEvents.PaymentOutcome(p.orderId(), p.id(), p.customerId(), p.amount(), p.currency(),
                        r.status(), null, r.id(), r.amount()), causation);
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
