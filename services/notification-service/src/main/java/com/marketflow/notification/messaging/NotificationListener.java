package com.marketflow.notification.messaging;

import com.marketflow.notification.application.NotificationService;
import com.marketflow.notification.delivery.EmailSender;
import com.marketflow.platform.events.EventDeserializationException;
import com.marketflow.platform.events.NonRetryableEventException;
import com.marketflow.platform.kafka.EventListenerSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Non-blocking retries: a failing message is moved to {@code <topic>-notification-retry-N} (1s, 2s, 4s, capped at
 * 10s) so it does not block the main partition; after the last attempt it lands in {@code <topic>-notification-dlt}.
 * Permanent failures (bounced address, malformed event) skip the retry topics and go straight to the DLT.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
    static final Set<String> TYPES = Set.of("UserRegistered", "VendorApplicationSubmitted", "VendorApproved",
            "VendorRejected", "VendorSuspended", "VendorReinstated", "VendorProfileUpdated", "OrderConfirmed",
            "OrderCancelled", "ShipmentCreated", "OrderDelivered", "LowStockDetected", "ChatMessageSent");

    private final EventListenerSupport support;
    private final NotificationService notifications;
    private final MeterRegistry meters;

    public NotificationListener(EventListenerSupport support, NotificationService notifications, MeterRegistry meters) {
        this.support = support;
        this.notifications = notifications;
        this.meters = meters;
        // created at 0 so the first dead letter shows up in increase()/rate() (see KafkaErrorHandling)
        for (String source : new String[] {"identity.user.events", "vendor.events", "order.events", "inventory.events",
                "chat.events"}) {
            meters.counter("notifications.dlt", "topic", source + "-notification-dlt");
        }
    }

    @RetryableTopic(
            attempts = "${marketflow.notification.max-attempts:4}",
            backoff = @Backoff(delayExpression = "${marketflow.notification.initial-backoff-ms:1000}", multiplier = 2.0,
                    maxDelayExpression = "${marketflow.notification.max-backoff-ms:10000}"),
            retryTopicSuffix = "-notification-retry",
            dltTopicSuffix = "-notification-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "true",
            numPartitions = "1",
            replicationFactor = "${marketflow.kafka.replication-factor:1}",
            exclude = {EmailSender.PermanentDeliveryException.class, EventDeserializationException.class,
                    NonRetryableEventException.class})
    @KafkaListener(topics = {"identity.user.events", "vendor.events", "order.events", "inventory.events",
            "chat.events"},
            groupId = "notification-service")
    public void onEvent(String message) {
        support.handle(message, e -> {
            if (!TYPES.contains(e.eventType())) {
                return;
            }
            support.codec().requireSupportedVersion(e, 1);
            notifications.process(e);
        });
    }

    @DltHandler
    public void onDeadLetter(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                             @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        meters.counter("notifications.dlt", "topic", topic).increment();
        try {
            var e = support.codec().parse(message);
            notifications.markExhausted(e.eventId(), error == null ? "unknown" : error);
            log.error("Notification event {} ({}) dead-lettered on {}: {}", e.eventId(), e.eventType(), topic, error);
        } catch (RuntimeException parseFailure) {
            log.error("Unparseable message dead-lettered on {}: {}", topic, error);
        }
    }
}
