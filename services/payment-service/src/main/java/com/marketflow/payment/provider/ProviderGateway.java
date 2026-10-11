package com.marketflow.payment.provider;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Wraps every provider call with Resilience4j policies (configured under {@code resilience4j.*.instances.provider}):
 * <ul>
 *   <li><b>TimeLimiter</b> – bounds latency. A timeout is reported as {@link ProviderTimeoutException}: the
 *       outcome is unknown and the payment goes to reconciliation.</li>
 *   <li><b>CircuitBreaker</b> – stops hammering a failing provider; an open circuit means the call was never
 *       made ({@link ProviderUnavailableException}) and it is safe to retry later.</li>
 *   <li><b>Bulkhead</b> – caps concurrent provider calls so a slow provider cannot exhaust all threads.</li>
 * </ul>
 * There is deliberately no automatic Retry here: retries happen at the business level (saga timeouts and
 * reconciliation) and are safe only because every provider call carries an idempotency key.
 */
@Component
public class ProviderGateway {

    private final PaymentProvider provider;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final ExecutorService executor = Executors.newFixedThreadPool(32, r -> {
        Thread t = new Thread(r, "provider-call");
        t.setDaemon(true);
        return t;
    });
    private final MeterRegistry meters;

    public ProviderGateway(PaymentProvider provider, TimeLimiterRegistry timeLimiters,
                           CircuitBreakerRegistry circuitBreakers, BulkheadRegistry bulkheads, MeterRegistry meters) {
        this.provider = provider;
        this.timeLimiter = timeLimiters.timeLimiter("provider");
        this.circuitBreaker = circuitBreakers.circuitBreaker("provider");
        this.bulkhead = bulkheads.bulkhead("provider");
        this.meters = meters;
    }

    public String providerName() {
        return provider.name();
    }

    public PaymentProvider.Result call(String operation, Supplier<PaymentProvider.Result> call) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "ok";
        try {
            Supplier<PaymentProvider.Result> guarded = Bulkhead.decorateSupplier(bulkhead,
                    CircuitBreaker.decorateSupplier(circuitBreaker, () -> withTimeLimit(call)));
            return guarded.get();
        } catch (CallNotPermittedException | BulkheadFullException e) {
            outcome = "not_attempted";
            throw new ProviderUnavailableException("provider call not attempted: " + e.getClass().getSimpleName(), e);
        } catch (ProviderTimeoutException e) {
            outcome = "timeout";
            throw e;
        } finally {
            sample.stop(meters.timer("payment.provider.calls", "operation", operation, "outcome", outcome));
        }
    }

    private PaymentProvider.Result withTimeLimit(Supplier<PaymentProvider.Result> call) {
        try {
            return timeLimiter.executeFutureSupplier(() -> CompletableFuture.supplyAsync(call, executor));
        } catch (TimeoutException e) {
            throw new ProviderTimeoutException("provider call exceeded time limit", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (RuntimeException e) {
            if (e.getCause() instanceof ProviderTimeoutException pte) {
                throw pte;
            }
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public PaymentProvider provider() {
        return provider;
    }
}
