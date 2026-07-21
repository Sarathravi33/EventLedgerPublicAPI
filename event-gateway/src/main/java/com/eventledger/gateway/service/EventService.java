package com.eventledger.gateway.service;

import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.api.FieldErrorView;
import com.eventledger.gateway.client.AccountServiceCallException;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.repository.EventRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final Validator validator;

    public EventService(EventRepository eventRepository, AccountServiceClient accountServiceClient,
                         Validator validator) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.validator = validator;
    }

    /**
     * Deliberately not wrapped in a single {@code @Transactional} boundary: the call to the
     * Account Service is a network round-trip (subject to timeout/retry in a later step), and
     * holding a database transaction open across it would tie up a connection for the
     * duration of that call. Each repository call below gets its own short transaction
     * (Spring Data JPA's default), with the downstream call happening in between with no
     * database transaction open at all.
     */
    public EventSubmissionResult submit(EventRequest request) {
        validate(request);

        return eventRepository.findByEventId(request.eventId())
                .map(this::handleExisting)
                .orElseGet(() -> handleNew(request));
    }

    private void validate(EventRequest request) {
        Set<ConstraintViolation<EventRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            List<FieldErrorView> errors = violations.stream()
                    .map(v -> new FieldErrorView(v.getPropertyPath().toString(), v.getMessage()))
                    .toList();
            throw new EventValidationException(errors);
        }
    }

    private EventSubmissionResult handleExisting(Event event) {
        if (event.getStatus() == EventStatus.APPLIED) {
            return new EventSubmissionResult(event, true);
        }
        // FAILED (Account Service was unreachable last time): a resubmission of the same
        // eventId is a genuine retry, not a no-op duplicate — attempt it again on the same row.
        return applyDownstream(event);
    }

    private EventSubmissionResult handleNew(EventRequest request) {
        Event event = eventRepository.save(new Event(request.eventId(), request.accountId(), request.type(),
                request.amount(), request.currency(), request.eventTimestamp(), request.metadata(), null,
                Instant.now()));
        return applyDownstream(event);
    }

    private EventSubmissionResult applyDownstream(Event event) {
        try {
            accountServiceClient.applyTransaction(event.getAccountId(), event.getEventId(), event.getType(),
                    event.getAmount(), event.getEventTimestamp());
            event.markApplied();
        } catch (AccountServiceCallException e) {
            event.markFailed(e.getMessage());
        }
        eventRepository.save(event);
        return new EventSubmissionResult(event, false);
    }
}
