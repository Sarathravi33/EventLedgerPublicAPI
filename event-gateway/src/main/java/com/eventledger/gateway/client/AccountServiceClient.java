package com.eventledger.gateway.client;

import com.eventledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Contract for the Gateway's outbound call to the Account Service (§6.2 of
 * IMPLEMENTATION_PLAN.md). The real HTTP-backed implementation is wired in a later step; until
 * then, callers depend only on this interface so the idempotency/replay orchestration in
 * {@code EventService} can be developed and tested without a live dependency.
 */
public interface AccountServiceClient {

    AccountServiceApplyResult applyTransaction(String accountId, String eventId, EventType type,
                                                BigDecimal amount, Instant eventTimestamp);
}
