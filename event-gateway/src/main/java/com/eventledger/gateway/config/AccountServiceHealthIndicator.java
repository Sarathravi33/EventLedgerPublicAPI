package com.eventledger.gateway.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Informational only, per IMPLEMENTATION_PLAN.md §11/§13: reports whether the Account Service
 * is currently reachable as a sub-component, but always returns {@link Health#up()} for its
 * own status so it can never flip the Gateway's overall {@code /health} to {@code DOWN} — the
 * Gateway's own liveness must stay accurate even while correctly surfacing the downstream
 * outage. Uses the plain (non-resilience-wrapped) {@code RestClient} bean directly, deliberately
 * bypassing the circuit breaker — a health probe shouldn't consume the same failure budget as
 * business calls or be blocked by an open circuit it didn't cause.
 */
@Component
public class AccountServiceHealthIndicator implements HealthIndicator {

    private final RestClient restClient;

    public AccountServiceHealthIndicator(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    @Override
    public Health health() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return Health.up().withDetail("reachable", true).build();
        } catch (RestClientException e) {
            return Health.up().withDetail("reachable", false).withDetail("error", e.getMessage()).build();
        }
    }
}
