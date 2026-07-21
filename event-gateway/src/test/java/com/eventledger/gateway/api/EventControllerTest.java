package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.service.EventNotFoundException;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.EventSubmissionResult;
import com.eventledger.gateway.service.EventValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EventService eventService;

    private final Instant eventTimestamp = Instant.parse("2026-05-15T14:02:11Z");

    @Test
    void submit_newlyApplied_returns201() throws Exception {
        Event event = new Event("evt-1", "acct-1", EventType.CREDIT, new BigDecimal("100.00"), "USD",
                eventTimestamp, null, null, Instant.now());
        event.markApplied();
        when(eventService.submit(any())).thenReturn(new EventSubmissionResult(event, false));

        mockMvc.perform(post("/events").contentType("application/json").content(requestJson("evt-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    @Test
    void submit_pureDuplicate_returns200() throws Exception {
        Event event = new Event("evt-2", "acct-1", EventType.CREDIT, new BigDecimal("100.00"), "USD",
                eventTimestamp, null, null, Instant.now());
        event.markApplied();
        when(eventService.submit(any())).thenReturn(new EventSubmissionResult(event, true));

        mockMvc.perform(post("/events").contentType("application/json").content(requestJson("evt-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-2"));
    }

    @Test
    void submit_accountServiceFailed_returns503WithEventIdRetained() throws Exception {
        Event event = new Event("evt-3", "acct-1", EventType.CREDIT, new BigDecimal("100.00"), "USD",
                eventTimestamp, null, null, Instant.now());
        event.markFailed("Account Service unreachable");
        when(eventService.submit(any())).thenReturn(new EventSubmissionResult(event, false));

        mockMvc.perform(post("/events").contentType("application/json").content(requestJson("evt-3")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Account Service Unavailable"))
                .andExpect(jsonPath("$.eventId").value("evt-3"));
    }

    @Test
    void submit_validationFailure_returns400WithFieldErrors() throws Exception {
        when(eventService.submit(any())).thenThrow(
                new EventValidationException(List.of(new FieldErrorView("amount", "amount must be greater than 0"))));

        mockMvc.perform(post("/events").contentType("application/json").content(requestJson("evt-4")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    void submit_unknownType_returns400MalformedRequest() throws Exception {
        String body = """
                {"eventId":"evt-5","accountId":"acct-1","type":"FOO","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}
                """;

        mockMvc.perform(post("/events").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"));
    }

    @Test
    void getById_found_returns200() throws Exception {
        Event event = new Event("evt-6", "acct-1", EventType.DEBIT, new BigDecimal("20.00"), "USD",
                eventTimestamp, null, null, Instant.now());
        when(eventService.getByEventIdOrThrow("evt-6")).thenReturn(event);

        mockMvc.perform(get("/events/evt-6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-6"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(eventService.getByEventIdOrThrow("evt-missing")).thenThrow(new EventNotFoundException("evt-missing"));

        mockMvc.perform(get("/events/evt-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Event Not Found"));
    }

    @Test
    void listByAccount_returnsEvents() throws Exception {
        Event e1 = new Event("evt-7", "acct-1", EventType.CREDIT, new BigDecimal("5.00"), "USD",
                eventTimestamp, null, null, Instant.now());
        when(eventService.listByAccount("acct-1")).thenReturn(List.of(e1));

        mockMvc.perform(get("/events").param("account", "acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-7"));
    }

    private String requestJson(String eventId) {
        return """
                {"eventId":"%s","accountId":"acct-1","type":"CREDIT","amount":100.00,"currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}
                """.formatted(eventId);
    }
}
