package com.eventledger.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eventledger.account.AccountServiceApplication;
import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.api.EventResponse;
import com.eventledger.gateway.domain.EventType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stronger end-to-end proof than {@link TracePropagationTest}: boots a real Account Service
 * (not WireMock) and asserts the *same* traceId appears in both services' own captured log
 * output for one request — directly demonstrating "a single client request produces a
 * traceable path across both services" (assignment requirement #3), not just that a header
 * was sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossServiceTracePropagationTest {

    private static ConfigurableApplicationContext accountServiceContext;

    @DynamicPropertySource
    static void accountServiceProperties(DynamicPropertyRegistry registry) {
        accountServiceContext = new SpringApplicationBuilder(AccountServiceApplication.class)
                .run("--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:accountdb-trace-it;DB_CLOSE_DELAY=-1",
                        "--spring.application.name=account-service-trace-it");
        int port = ((ServletWebServerApplicationContext) accountServiceContext).getWebServer().getPort();
        registry.add("account-service.base-url", () -> "http://localhost:" + port);
    }

    @AfterAll
    static void stopAccountService() {
        if (accountServiceContext != null) {
            accountServiceContext.close();
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private ListAppender<ILoggingEvent> gatewayLogCapture;
    private ListAppender<ILoggingEvent> accountServiceLogCapture;

    @BeforeEach
    void attachLogCaptures() {
        gatewayLogCapture = new ListAppender<>();
        gatewayLogCapture.start();
        gatewayLogger().addAppender(gatewayLogCapture);

        accountServiceLogCapture = new ListAppender<>();
        accountServiceLogCapture.start();
        accountServiceLogger().addAppender(accountServiceLogCapture);
    }

    @AfterEach
    void detachLogCaptures() {
        gatewayLogger().detachAppender(gatewayLogCapture);
        accountServiceLogger().detachAppender(accountServiceLogCapture);
    }

    private Logger gatewayLogger() {
        return (Logger) LoggerFactory.getLogger("com.eventledger.gateway");
    }

    private Logger accountServiceLogger() {
        // Both services run in this one JVM for this test, so Logback's LoggerContext (a
        // per-JVM singleton) is shared — no need to reach into accountServiceContext for it.
        return (Logger) LoggerFactory.getLogger("com.eventledger.account");
    }

    @Test
    void singleRequest_producesMatchingTraceIdInBothServicesLogs() {
        EventRequest request = new EventRequest("evt-cross-trace-1", "acct-cross-trace", EventType.CREDIT,
                new BigDecimal("15.00"), "USD", Instant.parse("2026-05-15T14:02:11Z"), null);

        ResponseEntity<EventResponse> response = restTemplate.postForEntity("/events", request, EventResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var gatewayTraceIds = gatewayLogCapture.list.stream()
                .map(e -> e.getMDCPropertyMap().get("traceId"))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        var accountServiceTraceIds = accountServiceLogCapture.list.stream()
                .map(e -> e.getMDCPropertyMap().get("traceId"))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(gatewayTraceIds).as("Gateway should have logged with a traceId").isNotEmpty();
        assertThat(accountServiceTraceIds).as("Account Service should have logged with a traceId").isNotEmpty();
        assertThat(accountServiceTraceIds)
                .as("Account Service's traceId(s) %s should overlap with the Gateway's %s for this single request",
                        accountServiceTraceIds, gatewayTraceIds)
                .containsAnyElementsOf(gatewayTraceIds);
    }
}
