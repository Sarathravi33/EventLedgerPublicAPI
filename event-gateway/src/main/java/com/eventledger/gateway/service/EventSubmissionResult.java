package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.Event;

/**
 * {@code pureDuplicate} is true only when an already-{@code APPLIED} event was found and the
 * Account Service was <em>not</em> called again. Every other path (new event, or a retry of a
 * previously {@code FAILED} event) sets it false, so the caller can derive the right HTTP
 * status purely from {@code pureDuplicate} and {@code event.getStatus()}: pureDuplicate → 200,
 * else APPLIED → 201, else (FAILED) → 503.
 */
public record EventSubmissionResult(Event event, boolean pureDuplicate) {
}
