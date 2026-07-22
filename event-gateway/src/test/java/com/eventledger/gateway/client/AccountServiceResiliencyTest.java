package com.eventledger.gateway.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises the resiliency layer (IMPLEMENTATION_PLAN.md §12) against a WireMock stand-in for
 * the Account Service. Resilience4j properties are overridden to small, fast values here purely
 * so the tests run quickly and deterministically — the logic under test (timeout, retry,
 * circuit breaker) is the same regardless of the concrete durations/thresholds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.timelimiter.instances.accountService.timeout-duration=300ms",
        "resilience4j.retry.instances.accountService.max-attempts=2",
        "resilience4j.retry.instances.accountService.wait-duration=50ms",
        "resilience4j.retry.instances.accountService.enable-exponential-backoff=false",
        "resilience4j.circuitbreaker.instances.accountService.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.accountService.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.accountService.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=5s"
})
class AccountServiceResiliencyTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void accountServiceBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", wireMock::baseUrl);
    }

    @Autowired
    private AccountServiceClient accountServiceClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TestRestTemplate restTemplate;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetState() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("accountService");
        circuitBreaker.reset();
        wireMock.resetAll();
    }

    @Test
    void timeoutExceeded_failsFastRatherThanWaitingForTheFullDelay() {
        wireMock.stubFor(get(urlPathMatching("/accounts/.*/balance"))
                .willReturn(aResponse().withFixedDelay(3000).withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountId\":\"acct-timeout\",\"balance\":1.00}")));

        long start = System.nanoTime();
        assertAccountServiceCallExceptionThrown(() -> accountServiceClient.getBalance("acct-timeout"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Budget: 2 attempts x 300ms timeout + 50ms backoff =~ 650ms. A generous upper bound
        // well below the stub's 3000ms delay proves the call failed at the configured timeout
        // boundary, not by eventually waiting out some longer client/HTTP default.
        assertThat(elapsedMs).isLessThan(2000);
    }

    @Test
    void repeatedFailures_openTheCircuitAndStopCallingDownstream() {
        wireMock.stubFor(get(urlPathMatching("/accounts/.*/balance")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 4; i++) {
            int idx = i;
            assertAccountServiceCallExceptionThrown(() -> accountServiceClient.getBalance("acct-fail-" + idx));
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestsBeforeNextCall = wireMock.findAll(getRequestedFor(urlPathMatching("/accounts/.*/balance"))).size();

        assertAccountServiceCallExceptionThrown(() -> accountServiceClient.getBalance("acct-fail-after-open"));

        int requestsAfterNextCall = wireMock.findAll(getRequestedFor(urlPathMatching("/accounts/.*/balance"))).size();
        assertThat(requestsAfterNextCall)
                .as("circuit should be open, so this call must never reach WireMock at all")
                .isEqualTo(requestsBeforeNextCall);
    }

    @Test
    void transientFailureThenSuccess_retryRecoversWithoutSurfacingAnError() {
        wireMock.stubFor(get(urlPathMatching("/accounts/acct-retry/balance"))
                .inScenario("retry-recovery")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));

        wireMock.stubFor(get(urlPathMatching("/accounts/acct-retry/balance"))
                .inScenario("retry-recovery")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"accountId\":\"acct-retry\",\"balance\":42.00}")));

        BigDecimal balance = accountServiceClient.getBalance("acct-retry");

        assertThat(balance).isEqualByComparingTo("42.00");
        assertThat(wireMock.findAll(getRequestedFor(urlPathMatching("/accounts/acct-retry/balance")))).hasSize(2);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void getEventById_and_listByAccount_stillWorkWhileCircuitIsOpen() {
        openTheCircuit();

        ResponseEntity<Map<String, Object>> byId = restTemplate.exchange(
                "/events/does-not-exist", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        // A local read for a genuinely-missing event: a clean 404, never a hang or a 500
        // caused by the downstream circuit being open — these endpoints never call it.
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<List<Object>> byAccount = restTemplate.exchange(
                "/events?account=acct-any", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Object>>() {
                });
        assertThat(byAccount.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void postEvents_whenCircuitIsOpen_returns503NotHangingOrErroring() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions")).willReturn(aResponse().withStatus(500)));
        openTheCircuit();

        Map<String, Object> request = Map.of(
                "eventId", "evt-circuit-open",
                "accountId", "acct-circuit-open",
                "type", "CREDIT",
                "amount", 10.00,
                "currency", "USD",
                "eventTimestamp", Instant.parse("2026-05-15T14:02:11Z").toString());

        // Warm-up call outside the timed window: the first-ever HTTP request into this test's
        // Spring context pays a one-time DispatcherServlet/HandlerMapping initialization cost
        // (larger now that springdoc registers additional request mappings) that has nothing to
        // do with what this assertion is actually checking — that an open circuit fails
        // immediately with no network call. Without this, the timing bound below flaked at
        // ~1.1-1.2s on the very first real request in the class. See FIXES.md.
        restTemplate.getForEntity("/health", String.class);

        long start = System.nanoTime();
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/events", HttpMethod.POST, new org.springframework.http.HttpEntity<>(request),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("eventId", "evt-circuit-open");
        // Circuit open => fails immediately with no network call at all, not merely "fast".
        assertThat(elapsedMs).isLessThan(1000);
    }

    private void openTheCircuit() {
        wireMock.stubFor(get(urlPathMatching("/accounts/.*/balance")).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 4; i++) {
            int idx = i;
            assertAccountServiceCallExceptionThrown(() -> accountServiceClient.getBalance("acct-open-" + idx));
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void assertAccountServiceCallExceptionThrown(Runnable action) {
        try {
            action.run();
            fail("expected AccountServiceCallException");
        } catch (AccountServiceCallException expected) {
            // expected
        }
    }
}
