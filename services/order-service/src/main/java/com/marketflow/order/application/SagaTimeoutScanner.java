package com.marketflow.order.application;

import com.marketflow.order.persistence.SagaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Drives sagas whose deadline passed. This is also the restart-recovery mechanism: after a crash every
 * in-flight saga is still in the table with its deadline, so the scanner picks it up and resumes it.
 * Each saga is handled in its own transaction under a row lock, so several order-service replicas can scan
 * concurrently without double-processing.
 */
@Component
public class SagaTimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutScanner.class);

    private final SagaRepository sagas;
    private final CheckoutSagaOrchestrator orchestrator;
    private final TransactionTemplate tx;
    private final Map<String, Long> stateCounts = new ConcurrentHashMap<>();

    public SagaTimeoutScanner(SagaRepository sagas, CheckoutSagaOrchestrator orchestrator, TransactionTemplate tx,
                              MeterRegistry meters) {
        this.sagas = sagas;
        this.orchestrator = orchestrator;
        this.tx = tx;
        for (var st : com.marketflow.order.domain.saga.SagaState.values()) {
            String name = st.name();
            Gauge.builder("saga.instances", stateCounts, m -> m.getOrDefault(name, 0L)).tag("state", name)
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${marketflow.order.saga.scan-interval:2s}")
    public void scan() {
        for (UUID orderId : sagas.findDue(50)) {
            try {
                tx.executeWithoutResult(status -> orchestrator.onTimeout(orderId));
            } catch (RuntimeException e) {
                log.warn("Timeout handling for saga {} failed: {}", orderId, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 15_000)
    public void refreshGauges() {
        try {
            stateCounts.clear();
            sagas.countByState().forEach(c -> stateCounts.put(c.getState().name(), c.getTotal()));
        } catch (RuntimeException e) {
            log.debug("Saga gauge refresh failed: {}", e.getMessage());
        }
    }
}
