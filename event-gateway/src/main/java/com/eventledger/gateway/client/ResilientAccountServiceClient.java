package com.eventledger.gateway.client;

import com.eventledger.gateway.domain.EventType;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Wraps {@link RestAccountServiceClient} with the resiliency pattern from §12 of
 * IMPLEMENTATION_PLAN.md: {@code CircuitBreaker(Retry(TimeLimiter(call)))}. Composition order
 * (outermost to innermost) matters — TimeLimiter bounds each individual attempt, Retry wraps
 * that so a bounded number of attempts are each individually time-limited, and CircuitBreaker
 * wraps the whole retrying operation so it sees ONE success/failure outcome per logical call
 * rather than counting each retry attempt separately (which would trip the breaker on a single
 * flaky-but-recovering call).
 * <p>
 * Every failure mode — timeout, retries exhausted, or the circuit already open — is normalized
 * to {@link AccountServiceCallException} so {@code EventService} keeps a single failure signal
 * regardless of cause. {@link AccountNotFoundException} is deliberately left unwrapped and is
 * configured (see application.yml) to be ignored by both Retry and the CircuitBreaker: a 404 is
 * a legitimate business outcome, not an infrastructure failure.
 */
@Component
public class ResilientAccountServiceClient implements AccountServiceClient {

    private final RestAccountServiceClient delegate;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ResilientAccountServiceClient(RestAccountServiceClient delegate,
                                          TimeLimiterRegistry timeLimiterRegistry,
                                          RetryRegistry retryRegistry,
                                          CircuitBreakerRegistry circuitBreakerRegistry,
                                          MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.timeLimiter = timeLimiterRegistry.timeLimiter("accountService");
        this.retry = retryRegistry.retry("accountService");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("accountService");
        this.meterRegistry = meterRegistry;
    }

    @Override
    public AccountServiceApplyResult applyTransaction(String accountId, String eventId, EventType type,
                                                        BigDecimal amount, Instant eventTimestamp) {
        return execute(() -> delegate.applyTransaction(accountId, eventId, type, amount, eventTimestamp));
    }

    @Override
    public BigDecimal getBalance(String accountId) {
        return execute(() -> delegate.getBalance(accountId));
    }

    /**
     * The actual downstream call runs on {@link #executor} (a different thread than the
     * caller), which {@link TimeLimiter#decorateFutureSupplier} requires. The current span
     * lives in OpenTelemetry's own {@link Context}, which — like any thread-local-backed
     * context — does not automatically follow onto that new thread. Without capturing it here
     * ({@code Context.current()}) and re-activating it on the executor thread
     * ({@code makeCurrent()}), the outbound call would carry no {@code traceparent} header at
     * all, since the RestClient's tracing instrumentation would find no current span to
     * propagate — caught by {@code TracePropagationTest} before this shipped (see FIXES.md,
     * Step 8).
     */
    private <T> T execute(Supplier<T> supplier) {
        Context otelContext = Context.current();
        Supplier<T> contextPropagatingSupplier = () -> {
            try (Scope scope = otelContext.makeCurrent()) {
                return supplier.get();
            }
        };
        Callable<T> timeLimited = TimeLimiter.decorateFutureSupplier(timeLimiter,
                () -> CompletableFuture.supplyAsync(contextPropagatingSupplier, executor));
        Callable<T> withRetry = Retry.decorateCallable(retry, timeLimited);
        Callable<T> withCircuitBreaker = CircuitBreaker.decorateCallable(circuitBreaker, withRetry);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = withCircuitBreaker.call();
            sample.stop(Timer.builder("gateway.account_service.call.duration")
                    .tag("outcome", "success").register(meterRegistry));
            return result;
        } catch (Exception e) {
            Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
            String outcome = cause instanceof CallNotPermittedException ? "circuit_open" : "failure";
            sample.stop(Timer.builder("gateway.account_service.call.duration")
                    .tag("outcome", outcome).register(meterRegistry));
            if (cause instanceof AccountNotFoundException notFound) {
                throw notFound;
            }
            if (cause instanceof AccountServiceCallException callException) {
                throw callException;
            }
            throw new AccountServiceCallException("Account Service call failed: " + cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? ": " + cause.getMessage() : ""), cause);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
