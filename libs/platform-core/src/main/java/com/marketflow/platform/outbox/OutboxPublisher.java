package com.marketflow.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Relays pending outbox rows to Kafka.
 *
 * <ul>
 *   <li>Rows are claimed with {@code FOR UPDATE SKIP LOCKED} so several instances can run concurrently without
 *       publishing the same row at the same time.</li>
 *   <li>Each send is awaited (acks=all, idempotent producer) before the row is marked PUBLISHED.</li>
 *   <li>If the process dies after Kafka acknowledged but before the UPDATE commits, the row is published again on
 *       the next run. Delivery is therefore <b>at-least-once</b>; consumers deduplicate by {@code eventId}.</li>
 *   <li>On the first failure in a batch the batch stops, so later events for the same aggregate are not
 *       published ahead of an earlier one. The failed row is retried with exponential backoff; after
 *       {@code maxAttempts} it is parked as FAILED and surfaced via the {@code outbox.failed} metric.</li>
 * </ul>
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final Counter published;
    private final Counter failures;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                           OutboxProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.published = Counter.builder("outbox.published").register(meters);
        this.failures = Counter.builder("outbox.publish.failures").register(meters);
        Gauge.builder("outbox.pending", this, OutboxPublisher::pendingCount).register(meters);
        Gauge.builder("outbox.failed", this, OutboxPublisher::failedCount).register(meters);
    }

    private record Row(long id, String topic, String key, String payload, String eventType, String eventId,
                       int attempts) {
    }

    @Scheduled(fixedDelayString = "${marketflow.outbox.poll-interval:100ms}")
    public void publishPending() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            Integer sent;
            do {
                sent = tx.execute(status -> publishBatch());
            } while (sent != null && sent >= props.getBatchSize());
        } catch (RuntimeException e) {
            // DB unavailable etc. Next scheduled run retries; never crash the scheduler thread.
            log.warn("Outbox relay run failed: {}", e.getMessage());
        }
    }

    /** Publishes one batch inside the caller's transaction. Returns number of rows published. */
    int publishBatch() {
        List<Row> rows = jdbc.query("""
                SELECT id, topic, msg_key, payload::text AS payload, event_type, event_id::text AS event_id, attempts
                FROM outbox_event
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("topic"), rs.getString("msg_key"),
                rs.getString("payload"), rs.getString("event_type"), rs.getString("event_id"),
                rs.getInt("attempts")), props.getBatchSize());

        int count = 0;
        for (Row row : rows) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
                record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventId", row.eventId().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(props.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                jdbc.update("UPDATE outbox_event SET status='PUBLISHED', published_at=now(), attempts=attempts+1 "
                        + "WHERE id=?", row.id());
                published.increment();
                count++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failures.increment();
                int attempts = row.attempts() + 1;
                boolean park = attempts >= props.getMaxAttempts();
                long backoffMs = Math.min(props.getMaxBackoff().toMillis(),
                        (long) (200 * Math.pow(2, Math.min(attempts, 16))));
                jdbc.update("""
                        UPDATE outbox_event SET attempts=?, status=?, last_error=?,
                               next_attempt_at = now() + (? * interval '1 millisecond')
                        WHERE id=?
                        """, attempts, park ? "FAILED" : "PENDING", truncate(e.toString()), backoffMs, row.id());
                log.warn("Outbox publish failed eventType={} eventId={} attempt={} parked={}",
                        row.eventType(), row.eventId(), attempts, park);
                break; // preserve per-aggregate order: do not publish later rows ahead of this one
            }
        }
        return count;
    }

    @Scheduled(cron = "${marketflow.outbox.cleanup-cron:0 17 3 * * *}")
    public void cleanup() {
        Duration retention = props.getRetention();
        int deleted = jdbc.update("DELETE FROM outbox_event WHERE status='PUBLISHED' "
                + "AND published_at < now() - (? * interval '1 second')", retention.toSeconds());
        if (deleted > 0) {
            log.info("Outbox cleanup removed {} published rows", deleted);
        }
    }

    private double pendingCount() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status='PENDING'", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private double failedCount() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status='FAILED'", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static String truncate(String s) {
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
