package com.eventledger.gateway.repository;

import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class EventRepositoryTest {

    @Autowired
    private EventRepository eventRepository;

    @Test
    void enforcesUniqueEventId() {
        Instant now = Instant.now();
        eventRepository.saveAndFlush(newEvent("evt-dup", "acct-1", now, now));

        assertThatThrownBy(() -> eventRepository.saveAndFlush(newEvent("evt-dup", "acct-1", now, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ordersEventsChronologicallyRegardlessOfInsertionOrder() {
        Instant base = Instant.now();
        Event earliest = newEvent("evt-1", "acct-2", base.minus(10, ChronoUnit.SECONDS), base);
        Event middle = newEvent("evt-2", "acct-2", base.minus(5, ChronoUnit.SECONDS), base);
        Event latest = newEvent("evt-3", "acct-2", base, base);

        // Insert deliberately out of chronological order to prove the query — not insertion
        // order — determines the returned ordering.
        eventRepository.saveAndFlush(latest);
        eventRepository.saveAndFlush(earliest);
        eventRepository.saveAndFlush(middle);

        List<Event> ordered = eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-2");

        assertThat(ordered).extracting(Event::getEventId).containsExactly("evt-1", "evt-2", "evt-3");
    }

    @Test
    void metadataRoundTripsThroughJsonConversion() {
        Instant now = Instant.now();
        Event event = new Event("evt-4", "acct-3", EventType.CREDIT, new BigDecimal("10.00"), "USD", now,
                Map.of("source", "mainframe-batch", "batchId", "B-9042"), "trace-abc", now);

        eventRepository.saveAndFlush(event);
        eventRepository.flush();

        Event reloaded = eventRepository.findByEventId("evt-4").orElseThrow();

        assertThat(reloaded.getMetadata())
                .containsEntry("source", "mainframe-batch")
                .containsEntry("batchId", "B-9042");
    }

    @Test
    void metadataIsOptional() {
        Instant now = Instant.now();
        Event event = new Event("evt-5", "acct-4", EventType.DEBIT, new BigDecimal("5.00"), "USD", now,
                null, null, now);

        eventRepository.saveAndFlush(event);
        Event reloaded = eventRepository.findByEventId("evt-5").orElseThrow();

        assertThat(reloaded.getMetadata()).isNull();
    }

    private Event newEvent(String eventId, String accountId, Instant eventTimestamp, Instant createdAt) {
        return new Event(eventId, accountId, EventType.CREDIT, new BigDecimal("1.00"), "USD",
                eventTimestamp, null, null, createdAt);
    }
}
