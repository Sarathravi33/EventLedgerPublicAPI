package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.EventSubmissionResult;
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
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
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
    public EventResponse getById(@PathVariable String id) {
        return EventResponse.from(eventService.getByEventIdOrThrow(id));
    }

    @GetMapping
    public List<EventResponse> listByAccount(@RequestParam("account") String account) {
        return eventService.listByAccount(account).stream().map(EventResponse::from).toList();
    }
}
