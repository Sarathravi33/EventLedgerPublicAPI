package com.eventledger.gateway.service;

import com.eventledger.gateway.api.FieldErrorView;

import java.util.List;

public class EventValidationException extends RuntimeException {

    private final List<FieldErrorView> errors;

    public EventValidationException(List<FieldErrorView> errors) {
        super("Event validation failed: " + errors);
        this.errors = errors;
    }

    public List<FieldErrorView> getErrors() {
        return errors;
    }
}
