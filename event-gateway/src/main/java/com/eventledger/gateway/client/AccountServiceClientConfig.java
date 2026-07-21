package com.eventledger.gateway.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AccountServiceClientConfig {

    /**
     * A generous connect/read timeout as a safety net, not the primary control:
     * {@link ResilientAccountServiceClient}'s {@code TimeLimiter} is what normally bounds a
     * call's latency (per §12 of IMPLEMENTATION_PLAN.md). Cancelling a
     * {@code CompletableFuture} on timeout does not reliably interrupt a blocked HTTP thread,
     * so this socket-level timeout ensures an abandoned request eventually gives up its thread
     * on its own even if the cancellation didn't.
     * <p>
     * Takes the auto-configured {@link RestClient.Builder} as a parameter rather than calling
     * the static {@code RestClient.builder()} factory method, and explicitly registers
     * {@link TracingPropagationInterceptor} to guarantee the outbound {@code traceparent}
     * header (see that class's Javadoc for why this isn't left to auto-instrumentation alone).
     */
    @Bean
    public RestClient accountServiceRestClient(RestClient.Builder builder,
                                                @Value("${account-service.base-url}") String baseUrl,
                                                TracingPropagationInterceptor tracingPropagationInterceptor) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(5));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory)
                .requestInterceptor(tracingPropagationInterceptor)
                .build();
    }
}
