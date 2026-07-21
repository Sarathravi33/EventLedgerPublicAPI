package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventResponse(String eventId, String accountId, EventType type, BigDecimal amount, String currency,
                             Instant eventTimestamp, Map<String, Object> metadata, EventStatus status,
                             String failureReason, Instant createdAt) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.getEventId(), event.getAccountId(), event.getType(), event.getAmount(),
                event.getCurrency(), event.getEventTimestamp(), event.getMetadata(), event.getStatus(),
                event.getFailureReason(), event.getCreatedAt());
    }
}
