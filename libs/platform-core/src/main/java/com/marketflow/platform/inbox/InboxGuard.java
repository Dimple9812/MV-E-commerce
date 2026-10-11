package com.marketflow.platform.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Durable consumer-side deduplication ("inbox").
 *
 * <p>Kafka delivers at-least-once: a message can be redelivered after a rebalance, a consumer crash before the
 * offset commit, or because the outbox relay published it twice. The guard inserts {@code (consumer, eventId)}
 * into {@code processed_event} in the SAME transaction as the business update. If the business update rolls back
 * so does the marker, and the redelivery will be processed. If it commits, any later duplicate is skipped.
 *
 * <p>Producer idempotence ({@code enable.idempotence=true}) only removes duplicates caused by producer retries
 * within one producer session; it does not protect against the cases above, and neither mechanism alone gives
 * end-to-end business idempotency. That additionally needs idempotent commands (e.g. one reservation per order,
 * one payment per order) which each service enforces with unique constraints.
 */
public class InboxGuard {

    private final JdbcTemplate jdbc;

    public InboxGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time {@code consumer} sees {@code eventId}; false for a duplicate. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String consumer, UUID eventId) {
        int inserted = jdbc.update("""
                INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, consumer, eventId);
        return inserted == 1;
    }
}
