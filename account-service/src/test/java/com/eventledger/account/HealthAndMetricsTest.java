package com.eventledger.account;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthAndMetricsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void health_isServedAtLiteralPathAndReportsUp() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/health", org.springframework.http.HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("UP");
        // show-details: always should surface the DB connectivity sub-component.
        assertThat(response.getBody()).containsKey("components");
    }

    @Test
    void applyingTransaction_incrementsAppliedCounter() {
        double before = counterValue("CREDIT");

        String accountId = "acct-metrics-1";
        Map<String, Object> request = Map.of(
                "eventId", "evt-metrics-1",
                "type", "CREDIT",
                "amount", 10.00,
                "eventTimestamp", Instant.parse("2026-05-15T14:02:11Z").toString());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/accounts/{id}/transactions", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(request),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                }, accountId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(counterValue("CREDIT")).isEqualTo(before + 1);
    }

    private double counterValue(String type) {
        var counter = meterRegistry.find("account.transactions.applied").tag("type", type).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
