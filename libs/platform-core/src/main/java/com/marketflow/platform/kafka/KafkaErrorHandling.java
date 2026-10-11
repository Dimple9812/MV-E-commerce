package com.marketflow.platform.kafka;

import com.marketflow.platform.events.NonRetryableEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.charset.StandardCharsets;

/**
 * Builds the consumer error handler used by all services.
 *
 * <ul>
 *   <li>Retryable failures (DB deadlock, transient connectivity, optimistic lock) are retried in-place with a
 *       bounded exponential backoff (default 4 retries: 0.5s, 1s, 2s, 4s).</li>
 *   <li>Non-retryable failures ({@link NonRetryableEventException}, deserialization errors, invalid arguments)
 *       skip retries.</li>
 *   <li>Exhausted or non-retryable records go to {@code <topic>.dlt} with Spring Kafka's diagnostic headers
 *       ({@code kafka_dlt-exception-fqcn}, {@code kafka_dlt-exception-message}, original topic/partition/offset)
 *       plus {@code x-failed-consumer}. A counter {@code kafka.dlt.published} drives the DLT alert.</li>
 * </ul>
 * The offset is committed after the DLT publish succeeds, so the partition is never blocked forever by one
 * poison message and the message is never silently dropped.
 */
public final class KafkaErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandling.class);

    private KafkaErrorHandling() {
    }

    public static String dltTopic(String topic) {
        return topic + ".dlt";
    }

    /** Pre-creates {@code kafka.dlt.published} for every consumed topic so its first increment is observable. */
    public static void registerDltCounters(MeterRegistry meters, String[] consumedTopics, String consumerName) {
        for (String topic : consumedTopics) {
            if (!topic.isBlank()) {
                meters.counter("kafka.dlt.published", "topic", topic.trim(), "consumer", consumerName);
            }
        }
    }

    public static DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template, MeterRegistry meters,
                                                   String consumerName, int maxRetries) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (ConsumerRecord<?, ?> record, Exception ex) -> new TopicPartition(dltTopic(record.topic()), -1));
        recoverer.setHeadersFunction((record, ex) -> {
            org.apache.kafka.common.header.internals.RecordHeaders headers =
                    new org.apache.kafka.common.header.internals.RecordHeaders();
            headers.add("x-failed-consumer", consumerName.getBytes(StandardCharsets.UTF_8));
            meters.counter("kafka.dlt.published", "topic", record.topic(), "consumer", consumerName).increment();
            log.error("Routing record to DLT topic={} partition={} offset={} consumer={} cause={}",
                    record.topic(), record.partition(), record.offset(), consumerName,
                    ex.getCause() != null ? ex.getCause().getClass().getSimpleName() : ex.getClass().getSimpleName());
            return headers;
        });

        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(5_000);
        backOff.setMaxAttempts(maxRetries);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NonRetryableEventException.class, DeserializationException.class,
                IllegalArgumentException.class, NullPointerException.class, ClassCastException.class);
        handler.setRetryListeners((record, ex, attempt) ->
                log.warn("Retrying record topic={} offset={} attempt={} cause={}", record.topic(), record.offset(),
                        attempt, ex.getClass().getSimpleName()));
        return handler;
    }
}
