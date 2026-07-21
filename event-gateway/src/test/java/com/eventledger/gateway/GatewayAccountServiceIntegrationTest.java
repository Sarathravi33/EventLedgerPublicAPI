package com.eventledger.gateway;

import com.eventledger.account.AccountServiceApplication;
import com.eventledger.gateway.api.BalanceResponse;
import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.api.EventResponse;
import com.eventledger.gateway.domain.EventType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a real Account Service Spring context in the same JVM (on a random port) alongside the
 * Gateway's own test context, and drives both over real HTTP — proving the full Gateway →
 * Account Service flow end-to-end rather than against a mock. See the test-scope dependency
 * comment in event-gateway/pom.xml for why this is not a violation of "no shared code between
 * services" in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayAccountServiceIntegrationTest {

    private static ConfigurableApplicationContext accountServiceContext;

    private static final String ACCOUNT_ID = "acct-it-" + System.identityHashCode(new Object());
    private static final Instant BASE_TIME = Instant.parse("2026-05-15T14:00:00Z");

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Command-line-style args (not {@code SpringApplicationBuilder.properties(...)}, which sets
     * low-precedence "default properties" easily shadowed by any {@code application.yml} found
     * on the classpath) — needed because account-service is now on this module's test
     * classpath (see the pom.xml comment), so its {@code application.yml} sits at the exact
     * same classpath path as the Gateway's own. Without a high-precedence override here, this
     * context would silently pick up whichever {@code application.yml} the classloader resolves
     * first instead of its own, and could bind the wrong port or share the Gateway's database —
     * caught by inspecting this context's own startup log, not by a failing assertion (the two
     * services' H2 in-memory instance would still have worked without cross-contamination purely
     * by luck of non-overlapping table names). See FIXES.md (Step 6).
     */
    @DynamicPropertySource
    static void accountServiceProperties(DynamicPropertyRegistry registry) {
        accountServiceContext = new SpringApplicationBuilder(AccountServiceApplication.class)
                .run("--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:accountdb-it;DB_CLOSE_DELAY=-1",
                        "--spring.application.name=account-service-it");
        int port = ((ServletWebServerApplicationContext) accountServiceContext).getWebServer().getPort();
        registry.add("account-service.base-url", () -> "http://localhost:" + port);
    }

    @AfterAll
    static void stopAccountService() {
        if (accountServiceContext != null) {
            accountServiceContext.close();
        }
    }

    @Test
    @Order(1)
    void submitNewEvent_isAppliedAndReflectedInBalance() {
        EventRequest request = new EventRequest("evt-it-1", ACCOUNT_ID, EventType.CREDIT, new BigDecimal("100.00"),
                "USD", BASE_TIME, null);

        ResponseEntity<EventResponse> response = restTemplate.postForEntity("/events", request, EventResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status().name()).isEqualTo("APPLIED");

        BalanceResponse balance = restTemplate.getForObject("/accounts/{id}/balance", BalanceResponse.class, ACCOUNT_ID);
        assertThat(balance.balance()).isEqualByComparingTo("100.00");
    }

    @Test
    @Order(2)
    void duplicateSubmission_returns200AndDoesNotAlterBalance() {
        EventRequest request = new EventRequest("evt-it-1", ACCOUNT_ID, EventType.CREDIT, new BigDecimal("100.00"),
                "USD", BASE_TIME, null);

        ResponseEntity<EventResponse> response = restTemplate.postForEntity("/events", request, EventResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        BalanceResponse balance = restTemplate.getForObject("/accounts/{id}/balance", BalanceResponse.class, ACCOUNT_ID);
        assertThat(balance.balance()).isEqualByComparingTo("100.00");
    }

    @Test
    @Order(3)
    void outOfOrderEvents_areAppliedCorrectlyAndListedChronologically() {
        // Physically submitted second, but its eventTimestamp is the *latest* of the three.
        EventRequest debitLater = new EventRequest("evt-it-2", ACCOUNT_ID, EventType.DEBIT, new BigDecimal("30.00"),
                "USD", BASE_TIME.plus(10, ChronoUnit.SECONDS), null);
        ResponseEntity<EventResponse> debitResponse = restTemplate.postForEntity("/events", debitLater, EventResponse.class);
        assertThat(debitResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Physically submitted last, but its eventTimestamp is *earlier* than evt-it-1 — the
        // out-of-order case this system must tolerate.
        EventRequest creditEarliest = new EventRequest("evt-it-3", ACCOUNT_ID, EventType.CREDIT, new BigDecimal("10.00"),
                "USD", BASE_TIME.minus(5, ChronoUnit.SECONDS), null);
        ResponseEntity<EventResponse> creditResponse = restTemplate.postForEntity("/events", creditEarliest, EventResponse.class);
        assertThat(creditResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Balance is order-independent: 100 (evt-it-1) - 30 (evt-it-2) + 10 (evt-it-3) = 80,
        // regardless of the arrival order above.
        BalanceResponse balance = restTemplate.getForObject("/accounts/{id}/balance", BalanceResponse.class, ACCOUNT_ID);
        assertThat(balance.balance()).isEqualByComparingTo("80.00");

        ResponseEntity<List<EventResponse>> listResponse = restTemplate.exchange(
                "/events?account={account}", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<EventResponse>>() {
                }, ACCOUNT_ID);

        assertThat(listResponse.getBody())
                .extracting(EventResponse::eventId)
                .containsExactly("evt-it-3", "evt-it-1", "evt-it-2");
    }

    @Test
    @Order(4)
    void getById_returnsStoredEvent() {
        ResponseEntity<EventResponse> response = restTemplate.getForEntity("/events/evt-it-1", EventResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    @Order(5)
    void getById_unknownEvent_returns404() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/events/does-not-exist", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
