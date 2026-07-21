package com.eventledger.gateway.service;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String eventId) {
        super("Event not found: " + eventId);
    }
}
