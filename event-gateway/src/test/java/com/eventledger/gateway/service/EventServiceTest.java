package com.eventledger.gateway.service;

import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.client.AccountServiceApplyResult;
import com.eventledger.gateway.client.AccountServiceCallException;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.repository.EventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
class EventServiceTest {

    @Autowired
    private EventRepository eventRepository;

    private AccountServiceClient accountServiceClient;
    private EventService eventService;

    private final Instant eventTimestamp = Instant.parse("2026-05-15T14:02:11Z");

    @BeforeEach
    void setUp() {
        accountServiceClient = mock(AccountServiceClient.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        eventService = new EventService(eventRepository, accountServiceClient, validator, new SimpleMeterRegistry());
    }

    // ---- Validation matrix ----

    @Test
    void missingEventId_throwsValidationException() {
        EventRequest request = requestWith(null, "acct-1", EventType.CREDIT, new BigDecimal("10.00"), "USD");
        assertFieldError(request, "eventId");
    }

    @Test
    void missingAccountId_throwsValidationException() {
        EventRequest request = requestWith("evt-1", null, EventType.CREDIT, new BigDecimal("10.00"), "USD");
        assertFieldError(request, "accountId");
    }

    @Test
    void nullType_throwsValidationException() {
        EventRequest request = requestWith("evt-1", "acct-1", null, new BigDecimal("10.00"), "USD");
        assertFieldError(request, "type");
    }

    @Test
    void zeroAmount_throwsValidationException() {
        EventRequest request = requestWith("evt-1", "acct-1", EventType.CREDIT, BigDecimal.ZERO, "USD");
        assertFieldError(request, "amount");
    }

    @Test
    void negativeAmount_throwsValidationException() {
        EventRequest request = requestWith("evt-1", "acct-1", EventType.CREDIT, new BigDecimal("-5.00"), "USD");
        assertFieldError(request, "amount");
    }

    @Test
    void missingCurrency_throwsValidationException() {
        EventRequest request = requestWith("evt-1", "acct-1", EventType.CREDIT, new BigDecimal("10.00"), null);
        assertFieldError(request, "currency");
    }

    @Test
    void nullEventTimestamp_throwsValidationException() {
        EventRequest request = new EventRequest("evt-1", "acct-1", EventType.CREDIT, new BigDecimal("10.00"), "USD",
                null, null);
        assertFieldError(request, "eventTimestamp");
    }

    private void assertFieldError(EventRequest request, String expectedField) {
        assertThatThrownBy(() -> eventService.submit(request))
                .isInstanceOf(EventValidationException.class)
                .satisfies(ex -> assertThat(((EventValidationException) ex).getErrors())
                        .extracting(fe -> fe.field())
                        .contains(expectedField));
        verify(accountServiceClient, never()).applyTransaction(any(), any(), any(), any(), any());
    }

    // ---- New event ----

    @Test
    void newEvent_clientSucceeds_isPersistedAsApplied() {
        when(accountServiceClient.applyTransaction(eq("acct-1"), eq("evt-new-ok"), eq(EventType.CREDIT),
                any(BigDecimal.class), any(Instant.class)))
                .thenReturn(new AccountServiceApplyResult(new BigDecimal("150.00")));

        EventRequest request = requestWith("evt-new-ok", "acct-1", EventType.CREDIT, new BigDecimal("150.00"), "USD");
        EventSubmissionResult result = eventService.submit(request);

        assertThat(result.pureDuplicate()).isFalse();
        assertThat(result.event().getStatus()).isEqualTo(EventStatus.APPLIED);
        assertThat(eventRepository.findByEventId("evt-new-ok")).isPresent()
                .get().extracting(Event::getStatus).isEqualTo(EventStatus.APPLIED);
    }

    @Test
    void newEvent_clientFails_isPersistedAsFailedAndStillFetchable() {
        when(accountServiceClient.applyTransaction(eq("acct-1"), eq("evt-new-fail"), any(), any(), any()))
                .thenThrow(new AccountServiceCallException("Account Service unreachable"));

        EventRequest request = requestWith("evt-new-fail", "acct-1", EventType.CREDIT, new BigDecimal("50.00"), "USD");
        EventSubmissionResult result = eventService.submit(request);

        assertThat(result.pureDuplicate()).isFalse();
        assertThat(result.event().getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(result.event().getFailureReason()).isEqualTo("Account Service unreachable");

        Event fetched = eventRepository.findByEventId("evt-new-fail").orElseThrow();
        assertThat(fetched.getStatus()).isEqualTo(EventStatus.FAILED);
    }

    // ---- Pure duplicate (already APPLIED) ----

    @Test
    void duplicateOfAppliedEvent_returnsExistingWithoutCallingClient() {
        Event applied = eventRepository.saveAndFlush(new Event("evt-dup", "acct-1", EventType.CREDIT,
                new BigDecimal("20.00"), "USD", eventTimestamp, null, null, Instant.now()));
        applied.markApplied();
        eventRepository.saveAndFlush(applied);

        EventRequest request = requestWith("evt-dup", "acct-1", EventType.CREDIT, new BigDecimal("20.00"), "USD");
        EventSubmissionResult result = eventService.submit(request);

        assertThat(result.pureDuplicate()).isTrue();
        assertThat(result.event().getEventId()).isEqualTo("evt-dup");
        verify(accountServiceClient, never()).applyTransaction(any(), any(), any(), any(), any());
    }

    // ---- Retry of a previously FAILED event ----

    @Test
    void retryOfFailedEvent_succeedsNow_updatesSameRowInPlace() {
        Event failed = eventRepository.saveAndFlush(new Event("evt-retry-ok", "acct-1", EventType.CREDIT,
                new BigDecimal("30.00"), "USD", eventTimestamp, null, null, Instant.now()));
        failed.markFailed("previously unreachable");
        eventRepository.saveAndFlush(failed);

        when(accountServiceClient.applyTransaction(eq("acct-1"), eq("evt-retry-ok"), any(), any(), any()))
                .thenReturn(new AccountServiceApplyResult(new BigDecimal("30.00")));

        EventRequest request = requestWith("evt-retry-ok", "acct-1", EventType.CREDIT, new BigDecimal("30.00"), "USD");
        EventSubmissionResult result = eventService.submit(request);

        assertThat(result.pureDuplicate()).isFalse();
        assertThat(result.event().getStatus()).isEqualTo(EventStatus.APPLIED);
        assertThat(result.event().getId()).isEqualTo(failed.getId());

        List<Event> all = eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-1");
        assertThat(all).hasSize(1);
    }

    @Test
    void retryOfFailedEvent_failsAgain_staysFailedOnSameRow() {
        Event failed = eventRepository.saveAndFlush(new Event("evt-retry-fail", "acct-1", EventType.CREDIT,
                new BigDecimal("30.00"), "USD", eventTimestamp, null, null, Instant.now()));
        failed.markFailed("previously unreachable");
        eventRepository.saveAndFlush(failed);

        when(accountServiceClient.applyTransaction(eq("acct-1"), eq("evt-retry-fail"), any(), any(), any()))
                .thenThrow(new AccountServiceCallException("still unreachable"));

        EventRequest request = requestWith("evt-retry-fail", "acct-1", EventType.CREDIT, new BigDecimal("30.00"), "USD");
        EventSubmissionResult result = eventService.submit(request);

        assertThat(result.pureDuplicate()).isFalse();
        assertThat(result.event().getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(result.event().getFailureReason()).isEqualTo("still unreachable");
        assertThat(result.event().getId()).isEqualTo(failed.getId());

        List<Event> all = eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-1");
        assertThat(all).hasSize(1);
    }

    private EventRequest requestWith(String eventId, String accountId, EventType type, BigDecimal amount,
                                      String currency) {
        return new EventRequest(eventId, accountId, type, amount, currency, eventTimestamp, null);
    }
}
