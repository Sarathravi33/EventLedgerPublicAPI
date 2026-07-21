package com.eventledger.gateway.domain;

public enum EventStatus {
    /** Persisted locally, not yet (successfully) applied to the Account Service. */
    RECEIVED,
    /** Successfully applied to the Account Service. */
    APPLIED,
    /** The Account Service call failed (timeout/circuit-open/error); retained for retry/audit. */
    FAILED
}
