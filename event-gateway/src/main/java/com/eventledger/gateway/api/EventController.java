package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.EventSubmissionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/events")
@Tag(name = "Events", description = "Submit and query transaction events (public entry point)")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @Operation(summary = "Submit a transaction event",
            description = "Idempotent on eventId. Tolerates out-of-order arrival (balance is always "
                    + "order-independent). If the Account Service is unreachable, the event is still "
                    + "recorded locally (status FAILED) and 503 is returned — never a hang, never a 500.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "New event, successfully applied"),
            @ApiResponse(responseCode = "200", description = "Duplicate of an already-applied eventId — original record returned unchanged"),
            @ApiResponse(responseCode = "400", description = "Validation failed (missing field, non-positive amount, unknown type)"),
            @ApiResponse(responseCode = "503", description = "Account Service unreachable; event recorded with status FAILED for later retry")
    })
    public ResponseEntity<Object> submit(@RequestBody EventRequest request) {
        EventSubmissionResult result = eventService.submit(request);
        Event event = result.event();

        if (event.getStatus() == EventStatus.FAILED) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account Service is unavailable; the event was recorded but not applied: "
                            + event.getFailureReason());
            problem.setTitle("Account Service Unavailable");
            problem.setProperty("eventId", event.getEventId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
        }

        HttpStatus status = result.pureDuplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(EventResponse.from(event));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Retrieve a single event by its ID",
            description = "Served entirely from the Gateway's own database — never depends on the "
                    + "Account Service, so it keeps working even during an outage.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event found"),
            @ApiResponse(responseCode = "404", description = "No event with this eventId has been submitted")
    })
    public EventResponse getById(
            @Parameter(description = "The eventId supplied at submission time", example = "evt-001")
            @PathVariable String id) {
        return EventResponse.from(eventService.getByEventIdOrThrow(id));
    }

    @GetMapping
    @Operation(summary = "List events for an account, chronologically ordered",
            description = "Ordered by the event's own business timestamp, not arrival order — a "
                    + "late-arriving event with an earlier timestamp is placed correctly. Served "
                    + "entirely from the Gateway's own database.")
    @ApiResponse(responseCode = "200", description = "Events returned (possibly empty)")
    public List<EventResponse> listByAccount(
            @Parameter(description = "Account to list events for", example = "acct-123")
            @RequestParam("account") String account) {
        return eventService.listByAccount(account).stream().map(EventResponse::from).toList();
    }
}
