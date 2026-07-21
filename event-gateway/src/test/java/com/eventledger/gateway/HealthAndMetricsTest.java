package com.eventledger.gateway;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthAndMetricsTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void accountServiceBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", wireMock::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void health_overallStatusStaysUp_whenAccountServiceUnreachable() {
        wireMock.stubFor(get(urlPathMatching("/health")).willReturn(aResponse().withStatus(500)));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/health", HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("UP");

        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        Map<String, Object> accountServiceComponent = (Map<String, Object>) components.get("accountService");
        assertThat(accountServiceComponent.get("status")).isEqualTo("UP");
        Map<String, Object> details = (Map<String, Object>) accountServiceComponent.get("details");
        assertThat(details.get("reachable")).isEqualTo(false);
    }

    @Test
    void health_accountServiceComponent_reportsReachable_whenAccountServiceIsUp() {
        wireMock.stubFor(get(urlPathMatching("/health")).willReturn(okJson("{\"status\":\"UP\"}")));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/health", HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        Map<String, Object> accountServiceComponent = (Map<String, Object>) components.get("accountService");
        Map<String, Object> details = (Map<String, Object>) accountServiceComponent.get("details");
        assertThat(details.get("reachable")).isEqualTo(true);
    }

    @Test
    void submittingEvent_incrementsReceivedCounter() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(okJson("{\"accountId\":\"acct-metrics\",\"balance\":20.00}")));

        double before = counterValue("CREDIT", "created");

        Map<String, Object> request = Map.of(
                "eventId", "evt-metrics-gw-1",
                "accountId", "acct-metrics",
                "type", "CREDIT",
                "amount", 20.00,
                "currency", "USD",
                "eventTimestamp", Instant.parse("2026-05-15T14:02:11Z").toString());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/events", HttpMethod.POST, new HttpEntity<>(request),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(counterValue("CREDIT", "created")).isEqualTo(before + 1);
    }

    private double counterValue(String type, String outcome) {
        var counter = meterRegistry.find("gateway.events.received").tag("type", type).tag("outcome", outcome).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
