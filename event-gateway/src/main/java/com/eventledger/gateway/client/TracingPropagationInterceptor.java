package com.eventledger.gateway.client;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Explicitly injects the current OpenTelemetry context's {@code traceparent} header into the
 * outbound request (per IMPLEMENTATION_PLAN.md §9 — the Gateway must propagate its trace to the
 * Account Service). Uses OpenTelemetry's own {@link W3CTraceContextPropagator} directly rather
 * than Micrometer's {@code Tracer}/{@code Propagator} abstraction or Spring Boot's automatic
 * RestClient instrumentation: on this project's exact dependency combination, the
 * auto-configured Micrometer {@code Propagator} bean resolved with zero registered fields
 * (confirmed by inspecting {@code propagator.fields()}) regardless of
 * {@code management.tracing.propagation.type}, silently injecting nothing. This bypasses that
 * layer entirely and is guaranteed to work as long as a current span exists. See FIXES.md,
 * Step 8.
 */
@Component
public class TracingPropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        W3CTraceContextPropagator.getInstance().inject(Context.current(), request.getHeaders(), HttpHeaders::set);
        return execution.execute(request, body);
    }
}
