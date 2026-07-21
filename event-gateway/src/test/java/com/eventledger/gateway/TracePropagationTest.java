package com.eventledger.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
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

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the trace actually connects the two services rather than each just happening to log
 * *some* trace ID: extracts the traceId from the W3C {@code traceparent} header WireMock
 * captured on the outbound call, and independently asserts that exact value also shows up in
 * the Gateway's own captured log output for that same request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracePropagationTest {

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

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachLogCapture() {
        wireMock.resetAll();
        logCapture = new ListAppender<>();
        logCapture.start();
        gatewayLogger().addAppender(logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        gatewayLogger().detachAppender(logCapture);
    }

    private Logger gatewayLogger() {
        return (Logger) LoggerFactory.getLogger("com.eventledger.gateway");
    }

    @Test
    void traceIdOnOutboundCallMatchesTraceIdInGatewayLogs() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(okJson("{\"accountId\":\"acct-trace\",\"balance\":5.00}")));

        Map<String, Object> request = Map.of(
                "eventId", "evt-trace-1",
                "accountId", "acct-trace",
                "type", "CREDIT",
                "amount", 5.00,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:02:11Z");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/events", HttpMethod.POST, new HttpEntity<>(request),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
        assertThat(requests).hasSize(1);

        String traceparent = requests.get(0).getHeader("traceparent");
        assertThat(traceparent)
                .as("the outbound call to the Account Service must carry a W3C traceparent header")
                .isNotBlank();

        String traceIdFromHeader = extractTraceId(traceparent);

        boolean matchingLogLineExists = logCapture.list.stream()
                .anyMatch(event -> traceIdFromHeader.equals(event.getMDCPropertyMap().get("traceId")));

        assertThat(matchingLogLineExists)
                .as("expected a Gateway log line whose MDC traceId (%s) matches the traceparent header sent "
                        + "to the Account Service; captured MDC traceIds were: %s",
                        traceIdFromHeader,
                        logCapture.list.stream().map(e -> e.getMDCPropertyMap().get("traceId")).toList())
                .isTrue();
    }

    /** W3C traceparent format: {@code version-traceId(32 hex)-spanId(16 hex)-flags}. */
    private String extractTraceId(String traceparent) {
        String[] parts = traceparent.split("-");
        assertThat(parts).hasSizeGreaterThanOrEqualTo(3);
        return parts[1];
    }
}
