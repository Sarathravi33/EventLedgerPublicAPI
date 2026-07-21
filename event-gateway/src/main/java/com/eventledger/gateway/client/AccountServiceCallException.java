package com.eventledger.gateway.client;

/**
 * Signals that the call to the Account Service did not succeed — whatever the underlying
 * cause (timeout, circuit open, connection refused, non-2xx response). {@code EventService}
 * treats any instance of this as "downstream failed", marks the event {@code FAILED}, and
 * lets the caller respond with 503 rather than a hang or a 500.
 */
public class AccountServiceCallException extends RuntimeException {

    public AccountServiceCallException(String message) {
        super(message);
    }

    public AccountServiceCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
