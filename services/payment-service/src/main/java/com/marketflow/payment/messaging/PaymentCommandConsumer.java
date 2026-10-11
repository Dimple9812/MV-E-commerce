package com.marketflow.payment.messaging;

import com.marketflow.payment.application.PaymentEvents;
import com.marketflow.payment.application.PaymentService;
import com.marketflow.platform.kafka.EventListenerSupport;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Saga commands. Idempotency is by business key (one payment per order, one refund per refund key, operation
 * leases, provider idempotency keys) rather than by message ID, because the work spans several transactions and
 * a provider call.
 */
@Component
public class PaymentCommandConsumer {

    private final EventListenerSupport support;
    private final PaymentService payments;

    public PaymentCommandConsumer(EventListenerSupport support, PaymentService payments) {
        this.support = support;
        this.payments = payments;
    }

    @KafkaListener(topics = PaymentEvents.COMMANDS, groupId = "payment-service.commands")
    public void onCommand(String message) {
        support.handle(message, e -> {
            support.codec().requireSupportedVersion(e, 1);
            String causation = e.eventId().toString();
            switch (e.eventType()) {
                case "PaymentAuthorizationRequested" ->
                        payments.authorize(support.payload(e, PaymentEvents.AuthorizeCommand.class), causation);
                case "PaymentCaptureRequested" ->
                        payments.capture(support.payload(e, PaymentEvents.PaymentCommand.class).orderId(), causation);
                case "PaymentVoidRequested" -> {
                    var c = support.payload(e, PaymentEvents.PaymentCommand.class);
                    payments.voidPayment(c.orderId(), c.reason(), causation);
                }
                case "RefundRequested" -> {
                    var c = support.payload(e, PaymentEvents.PaymentCommand.class);
                    // one full refund per order+reason: repeating the command reuses the same refund
                    payments.refund(c.orderId(), null, c.reason() == null ? "refund" : c.reason(),
                            "order:" + c.orderId() + ":" + (c.reason() == null ? "refund" : c.reason()), null,
                            causation);
                }
                default -> {
                    // unknown command types are ignored (forward compatibility)
                }
            }
        });
    }
}
